// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.InlineFileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.SourceFileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.InjectionListener;
import com.google.devtools.build.lib.actions.MetadataConsumer;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.vfs.AbstractFileSystemWithCustomStat;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.RequestMetadata;
import io.grpc.Context;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.function.Function;
import javax.annotation.Nullable;

class RemoteActionFileSystem extends AbstractFileSystemWithCustomStat implements InjectionListener {

  private final FileSystem writeDelegate;
  private final FileSystem sourceDelegate;
  private final PathFragment execRootFragment;
  private final PathFragment outputPathFragment;
  private final ImmutableList<PathFragment> sourceRoots;
  private final ActionInputMap inputArtifactData;
  private final Iterable<Artifact> allowedInputs;
  private final Iterable<Artifact> outputArtifacts;
  private final Function<Integer, AbstractRemoteActionCache> locations;

  /** exec path → artifact and metadata */
  private final LoadingCache<PathFragment, OutputMetadata> outputs;

  private SkyFunction.Environment env = null;
  private MetadataConsumer metadataConsumer = null;

  public RemoteActionFileSystem(
      FileSystem writeDelegate,
      FileSystem sourceDelegate,
      PathFragment execRootFragment,
      String relativeOutputPath,
      ImmutableList<Root> sourceRoots,
      ActionInputMap inputArtifactData,
      Iterable<Artifact> allowedInputs,
      Iterable<Artifact> outputArtifacts,
      Function<Integer, AbstractRemoteActionCache> locations) {
    super(writeDelegate.getDigestFunction());
    this.writeDelegate = writeDelegate;
    this.sourceDelegate = sourceDelegate;
    this.execRootFragment = execRootFragment;
    this.outputPathFragment = execRootFragment.getRelative(relativeOutputPath);

    this.inputArtifactData = inputArtifactData;
    this.allowedInputs = allowedInputs;
    this.outputArtifacts = outputArtifacts;
    this.locations = locations;

    this.sourceRoots =
        sourceRoots
            .stream()
            .map(root -> root.asPath().asFragment())
                    .collect(ImmutableList.toImmutableList());

    ImmutableMap<PathFragment, Artifact> outputsMapping = Streams.stream(outputArtifacts)
        .collect(ImmutableMap.toImmutableMap(Artifact::getExecPath, a -> a));
    outputs = CacheBuilder.newBuilder().build(
    CacheLoader.from(path -> new OutputMetadata(outputsMapping.get(path))));
  }

  /**
   * Must be called prior to access and updated as needed.
   *
   * <p>These cannot be passed into the constructor because while {@link ActionFileSystem} is
   * action-scoped, the environment and metadata consumer change multiple times, at well defined
   * points, during the lifetime of an action.
   */
  void updateContext(SkyFunction.Environment env, MetadataConsumer metadataConsumer) {
    this.env = env;
    this.metadataConsumer = metadataConsumer;
  }

  // -------------------- InjectionListener Implementation --------------------

  @Override
  public void onInsert(Artifact dest, byte[] digest, long size, int backendIndex)
      throws IOException {
    outputs.getUnchecked(dest.getExecPath()).set(
        new RemoteFileArtifactValue(digest, size, backendIndex),
        TracingMetadataUtils.fromCurrentContext(),
        /*notifyConsumer=*/ true);
  }

  // -------------------- FileSystem implementation --------------------

  /**
   * Returns whether or not the FileSystem supports modifications of files and file entries.
   *
   * <p>Returns true if FileSystem supports the following:
   *
   * <ul>
   *   <li>{@link #setWritable(Path, boolean)}
   *   <li>{@link #setExecutable(Path, boolean)}
   * </ul>
   *
   * The above calls will result in an {@link UnsupportedOperationException} on a FileSystem where
   * this method returns {@code false}.
   */
  @Override
  public boolean supportsModifications(Path path) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns whether or not the FileSystem supports symbolic links.
   *
   * <p>Returns true if FileSystem supports the following:
   *
   * <ul>
   *   <li>{@link #createSymbolicLink(Path, PathFragment)}
   *   <li>{@link #getFileSize(Path, boolean)} where {@code followSymlinks=false}
   *   <li>{@link #getLastModifiedTime(Path, boolean)} where {@code followSymlinks=false}
   *   <li>{@link #readSymbolicLink(Path)} where the link points to a non-existent file
   * </ul>
   *
   * The above calls may result in an {@link UnsupportedOperationException} on a FileSystem where
   * this method returns {@code false}. The implementation can try to emulate these calls at its own
   * discretion.
   */
  @Override
  public boolean supportsSymbolicLinksNatively(Path path) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns whether or not the FileSystem supports hard links.
   *
   * <p>Returns true if FileSystem supports the following:
   *
   * <ul>
   *   <li>{@link #createFSDependentHardLink(Path, Path)}
   * </ul>
   *
   * The above calls may result in an {@link UnsupportedOperationException} on a FileSystem where
   * this method returns {@code false}. The implementation can try to emulate these calls at its own
   * discretion.
   */
  @Override
  protected boolean supportsHardLinksNatively(Path path) {
    throw new UnsupportedOperationException();
  }

  /***
   * Returns true if file path is case-sensitive on this file system. Default is true.
   */
  @Override
  public boolean isFilePathCaseSensitive() {
    throw new UnsupportedOperationException();
  }

  @Override
  protected FileStatus stat(Path path, boolean followSymlinks) throws IOException {
    FileArtifactValue metadata = getMetadataOrThrowFileNotFound(path);
    return new FileStatus() {
      @Override
      public boolean isFile() {
        return metadata.getType() == FileStateType.REGULAR_FILE;
      }

      @Override
      public boolean isDirectory() {
        // TODO(felly): Support directory awareness, and consider disallowing directory artifacts
        // eagerly at ActionFS construction.
        return false;
      }

      @Override
      public boolean isSymbolicLink() {
        // TODO(felly): We should have minimal support for symlink awareness when looking at
        // output --> src and src --> src symlinks.
        return false;
      }

      @Override
      public boolean isSpecialFile() {
        return metadata.getType() == FileStateType.SPECIAL_FILE;
      }

      @Override
      public long getSize() {
        return metadata.getSize();
      }

      @Override
      public long getLastModifiedTime() {
        return metadata.getModifiedTime();
      }

      @Override
      public long getLastChangeTime() {
        return metadata.getModifiedTime();
      }

      @Override
      public long getNodeId() {
        throw new UnsupportedOperationException();
      }
    };
  }

  /**
   * Returns the type of the file system path belongs to.
   */
  @Override
  public String getFileSystemType(Path path) {
    return "remote";
  }

  /**
   * Creates a directory with the name of the current path. See {@link Path#createDirectory} for
   * specification.
   */
  @Override
  public boolean createDirectory(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Creates all directories up to the path. See {@link Path#createDirectoryAndParents} for
   * specification.
   */
  @Override
  public void createDirectoryAndParents(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the size in bytes of the file denoted by {@code path}. See {@link
   * Path#getFileSize(Symlinks)} for specification.
   *
   * <p>Note: for <@link FileSystem>s where {@link #supportsSymbolicLinksNatively(Path)} returns
   * false, this method will throw an {@link UnsupportedOperationException} if {@code
   * followSymLinks=false}.
   */
  @Override
  protected long getFileSize(Path path, boolean followSymlinks) throws IOException {
    throw new UnsupportedOperationException();
  }

  /** Deletes the file denoted by {@code path}. See {@link Path#delete} for specification. */
  @Override
  public boolean delete(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the last modification time of the file denoted by {@code path}. See {@link
   * Path#getLastModifiedTime(Symlinks)} for specification.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsSymbolicLinksNatively(Path)} returns
   * false, this method will throw an {@link UnsupportedOperationException} if {@code
   * followSymLinks=false}.
   */
  @Override
  protected long getLastModifiedTime(Path path, boolean followSymlinks) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the last modification time of the file denoted by {@code path}. See {@link
   * Path#setLastModifiedTime} for specification.
   */
  @Override
  public void setLastModifiedTime(Path path, long newTime) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns value of the given extended attribute name or null if attribute
   * does not exist or file system does not support extended attributes. Follows symlinks.
   * <p>Default implementation assumes that file system does not support
   * extended attributes and always returns null. Specific file system
   * implementations should override this method if they do provide support
   * for extended attributes.
   *
   * @param path the file whose extended attribute is to be returned.
   * @param name the name of the extended attribute key.
   * @return the value of the extended attribute associated with 'path', if
   *   any, or null if no such attribute is defined (ENODATA) or file
   *   system does not support extended attributes at all.
   * @throws IOException if the call failed for any other reason.
   */
  @Override
  public byte[] getxattr(Path path, String name) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets a fast digest for the given path and hash function type, or {@code null} if there isn't
   * one available or the filesystem doesn't support them. This digest should be suitable for
   * detecting changes to the file.
   */
  @Override
  protected byte[] getFastDigest(Path path, DigestHashFunction hashFunction) throws IOException {
    // fixme lookup
    return null;
  }

  /**
   * Returns the digest of the file denoted by the path, following symbolic links, for the given
   * hash digest function.
   *
   * <p>Subclasses may (and do) optimize this computation for particular digest functions.
   *
   * @return a new byte array containing the file's digest
   * @throws IOException if the digest could not be computed for any reason
   */
  @Override
  protected byte[] getDigest(final Path path, DigestHashFunction hashFunction) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns true if "path" denotes an existing symbolic link. See
   * {@link Path#isSymbolicLink} for specification.
   */
  @Override
  protected boolean isSymbolicLink(Path path) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns true iff {@code path} denotes a special file.
   * See {@link Path#isSpecialFile(Symlinks)} for specification.
   */
  @Override
  protected boolean isSpecialFile(Path path, boolean followSymlinks) {
    throw new UnsupportedOperationException();
  }

  /**
   * Creates a symbolic link. See {@link Path#createSymbolicLink(Path)} for specification.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsSymbolicLinksNatively(Path)} returns
   * false, this method will throw an {@link UnsupportedOperationException}
   */
  @Override
  protected void createSymbolicLink(Path linkPath, PathFragment targetFragment)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the target of a symbolic link. See {@link Path#readSymbolicLink} for specification.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsSymbolicLinksNatively(Path)} returns
   * false, this method will throw an {@link UnsupportedOperationException} if the link points to a
   * non-existent file.
   *
   * @throws NotASymlinkException if the current path is not a symbolic link
   * @throws IOException if the contents of the link could not be read for any reason.
   */
  @Override
  protected PathFragment readSymbolicLink(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns true iff {@code path} denotes an existing file of any kind. See
   * {@link Path#exists(Symlinks)} for specification.
   */
  @Override
  protected boolean exists(Path path, boolean followSymlinks) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns a collection containing the names of all entities within the directory denoted by the
   * {@code path}.
   *
   * @throws IOException if there was an error reading the directory entries
   */
  @Override
  protected Collection<String> getDirectoryEntries(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns true iff the file represented by {@code path} is readable.
   *
   * @throws IOException if there was an error reading the file's metadata
   */
  @Override
  protected boolean isReadable(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the file to readable (if the argument is true) or non-readable (if the argument is false)
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsModifications(Path)} returns false or
   * which do not support unreadable files, this method will throw an {@link
   * UnsupportedOperationException}.
   *
   * @throws IOException if there was an error reading or writing the file's metadata
   */
  @Override
  protected void setReadable(Path path, boolean readable) throws IOException {
  }

  /**
   * Returns true iff the file represented by {@code path} is writable.
   *
   * @throws IOException if there was an error reading the file's metadata
   */
  @Override
  protected boolean isWritable(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the file to writable (if the argument is true) or non-writable (if the argument is false)
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsModifications(Path)} returns false, this
   * method will throw an {@link UnsupportedOperationException}.
   *
   * @throws IOException if there was an error reading or writing the file's metadata
   */
  @Override
  public void setWritable(Path path, boolean writable) throws IOException {
  }

  /**
   * Returns true iff the file represented by the path is executable.
   *
   * @throws IOException if there was an error reading the file's metadata
   */
  @Override
  protected boolean isExecutable(Path path) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the file to executable, if the argument is true. It is currently not supported to unset
   * the executable status of a file, so {code executable=false} yields an {@link
   * UnsupportedOperationException}.
   *
   * <p>Note: for {@link FileSystem}s where {@link #supportsModifications(Path)} returns false, this
   * method will throw an {@link UnsupportedOperationException}.
   *
   * @throws IOException if there was an error reading or writing the file's metadata
   */
  @Override
  protected void setExecutable(Path path, boolean executable) throws IOException {
  }

  protected static final String ERR_IS_DIRECTORY = " (Is a directory)";
  protected static final String ERR_DIRECTORY_NOT_EMPTY = " (Directory not empty)";
  protected static final String ERR_FILE_EXISTS = " (File exists)";
  protected static final String ERR_NO_SUCH_FILE_OR_DIR = " (No such file or directory)";
  protected static final String ERR_NOT_A_DIRECTORY = " (Not a directory)";

  /**
   * Creates an InputStream accessing the file denoted by the path.
   *
   * @throws IOException if there was an error opening the file for reading
   */
  @Override
  protected InputStream getInputStream(Path path) throws IOException {
    FileArtifactValue metadata = getMetadataChecked(asExecPath(path));
    InputStream inputStream = getInputStreamFromMetadata(metadata, path);
    if (inputStream != null) {
      return inputStream;
    }
    return getSourcePath(path.asFragment()).getInputStream();
  }

  @Nullable
  private InputStream getInputStreamFromMetadata(FileArtifactValue metadata, Path path)
      throws IOException {
    if (metadata instanceof InlineFileArtifactValue) {
      return ((InlineFileArtifactValue) metadata).getInputStream();
    }
    if (metadata instanceof SourceFileArtifactValue) {
      return resolveSourcePath((SourceFileArtifactValue) metadata).getInputStream();
    }
    if (metadata instanceof RemoteFileArtifactValue) {
      RemoteFileArtifactValue remoteFile = (RemoteFileArtifactValue) metadata;
      Digest digest = DigestUtil.buildDigest(remoteFile.getDigest(), remoteFile.getSize());
      AbstractRemoteActionCache cache = locations.apply(remoteFile.getLocationIndex());
      if (cache != null) {
        // FIXME needs to write to the file itself...
        return getInputStreamFromDigest(cache, getRequestMetadataChecked(asExecPath(path)), digest);
      }
    }
    return null;
  }

  private InputStream getInputStreamFromDigest(AbstractRemoteActionCache cache, RequestMetadata metadata, Digest digest)
      throws IOException {
    Context withMetadata = TracingMetadataUtils.contextWithMetadata(metadata);
    Context previous = withMetadata.attach();
    try {
      return new ByteArrayInputStream(Utils.getFromFuture(cache.downloadBlob(digest)));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      withMetadata.detach(previous);
    }
  }

  /**
   * Creates an OutputStream accessing the file denoted by path.
   *
   * @param append whether to open the output stream in append mode
   * @throws IOException if there was an error opening the file for writing
   */
  // this seems misplaced in FileSystem, and horrible for delegation
  @Override
  protected OutputStream getOutputStream(Path path, boolean append) throws IOException {
    // a number of issues:
    //   we want to use local filesystem to write contents, even if we're going to
    //   upload to remote. sourceDelegate is explicitly listed as non-output available
    //   we want to keep track of the digest
    //   we want to respect append
    //
    return outputs.getUnchecked(asExecPath(path)).getOutputStream(path, append);
  }

  /**
   * Renames the file denoted by "sourceNode" to the location "targetNode". See {@link
   * Path#renameTo} for specification.
   */
  @Override
  public void renameTo(Path sourcePath, Path targetPath) throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Create a new hard link file at "linkPath" for file at "originalPath".
   *
   * @param linkPath The path of the new link file to be created
   * @param originalPath The path of the original file
   * @throws IOException if there was an I/O error
   */
  @Override
  protected void createFSDependentHardLink(Path linkPath, Path originalPath)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Prefetch all directories and symlinks within the package
   * rooted at "path".  Enter at most "maxDirs" total directories.
   * Specializations for high-latency remote filesystems may wish to
   * implement this in order to warm the filesystem's internal caches.
   */
  protected void prefetchPackageAsync(Path path, int maxDirs) {
    throw new UnsupportedOperationException();
  }

  // -------------------- Implementation Helpers --------------------

  private PathFragment asExecPath(Path path) {
    return asExecPath(path.asFragment());
  }

  private PathFragment asExecPath(PathFragment fragment) {
    if (fragment.startsWith(execRootFragment)) {
      return fragment.relativeTo(execRootFragment);
    }
    for (PathFragment root : sourceRoots) {
      if (fragment.startsWith(root)) {
        return fragment.relativeTo(root);
      }
    }
    throw new IllegalArgumentException(
        fragment + " was not found under any known root: " + execRootFragment + ", " + sourceRoots);
  }

  @Nullable
  private FileArtifactValue getMetadataChecked(PathFragment execPath) throws IOException {
    {
      FileArtifactValue metadata = inputArtifactData.getMetadata(execPath.getPathString());
      if (metadata != null) {
        return metadata;
      }
    }
    /*
    {
      OptionalInputMetadata metadataHolder = optionalInputs.get(execPath);
      if (metadataHolder != null) {
        return metadataHolder.get();
      }
    }
    */
    {
      OutputMetadata metadataHolder = outputs.getIfPresent(execPath);
      if (metadataHolder != null) {
        FileArtifactValue metadata = metadataHolder.get();
        if (metadata != null) {
          return metadata;
        }
      }
    }
    return null;
  }

  @Nullable
  private RequestMetadata getRequestMetadataChecked(PathFragment execPath) throws IOException {
    OutputMetadata metadataHolder = outputs.getIfPresent(execPath);
    if (metadataHolder != null) {
      return metadataHolder.getRequestMetadata();
    }
    return null;
  }

  private FileArtifactValue getMetadataOrThrowFileNotFound(Path path) throws IOException {
    FileArtifactValue metadata = getMetadataChecked(asExecPath(path));
    if (metadata == null) {
      throw new FileNotFoundException(path.getPathString() + " was not found");
    }
    return metadata;
  }

  private Path getSourcePath(PathFragment path) throws IOException {
    if (path.startsWith(outputPathFragment)) {
      throw new IOException("ActionFS cannot delegate to underlying output path for " + path);
    }
    return sourceDelegate.getPath(path);
  }

  /** NB: resolves to the underlying filesytem instead of this one. */
  private Path resolveSourcePath(SourceFileArtifactValue metadata) throws IOException {
    return getSourcePath(sourceRoots.get(metadata.getSourceRootIndex()))
        .getRelative(metadata.getExecPath());
  }

  private class OutputMetadata {
    private final @Nullable Artifact artifact;
    @Nullable private volatile FileArtifactValue metadata = null;
    @Nullable private volatile RequestMetadata remoteMetadata = null;

    private OutputMetadata(Artifact artifact) {
      this.artifact = artifact;
    }

    @Nullable
    public FileArtifactValue get() {
      return metadata;
    }

    @Nullable
    public RequestMetadata getRequestMetadata() {
      return remoteMetadata;
    }

    /**
     * Sets the output metadata, and maybe notify the metadataConsumer.
     *
     * @param metadata the metadata to write
     * @param remoteMetadata the remote metadata to apply for any requests for this artifact
     * @param notifyConsumer whether to notify metadataConsumer. Callers should not notify the
     * metadataConsumer if it will be notified separately at the Spawn level.
     */
    public void set(FileArtifactValue metadata, RequestMetadata remoteMetadata, boolean notifyConsumer) throws IOException {
      if (notifyConsumer && artifact != null) {
        metadataConsumer.accept(artifact, metadata);
      }
      this.metadata = metadata;
      this.remoteMetadata = remoteMetadata;
    }

    /** Callers are expected to close the returned stream. */
    public ByteArrayOutputStream getOutputStream(Path path, boolean append) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream() {
        @Override
        public void close() throws IOException {
          flushInternal(true);
          super.close();
        }

        @Override
        public void flush() throws IOException {
          flushInternal(false);
        }

        private void flushInternal(boolean notify) throws IOException {
          super.flush();
          byte[] data = toByteArray();
          set(new InlineFileArtifactValue(data, writeDelegate.getDigestFunction().getHash().hashBytes(data).asBytes()),
              /*requestMetadata=*/ null,
              /*notifyConsumer=*/ notify);
        }
      };

      if (append && metadata != null) {
        try (InputStream in = getInputStreamFromMetadata(metadata, path)) {
          if (in == null) {
            throw new IOException("Unable to read file " + path + " with metadata " + metadata);
          }
          ByteStreams.copy(in, baos);
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }
      return baos;
    }
  }
}
