// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.MetadataConsumer;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.BatchStat;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

class RemoteOutputService implements OutputService {

  private EventHandler eventHandler;
  private UUID buildId;
  private boolean finalizeActions;

  private final Supplier<Path> execRootSupplier;
  private final FileSystem writeDelegate;
  private FileSystem actionFileSystem;
  private SkyFunction.Environment env;
  private MetadataConsumer consumer;
  private Function<Integer, AbstractRemoteActionCache> locations;

  RemoteOutputService(Supplier<Path> execRootSupplier, FileSystem writeDelegate, Function<Integer, AbstractRemoteActionCache> locations) {
    super();
    this.execRootSupplier = execRootSupplier;
    this.writeDelegate = writeDelegate;
    this.locations = locations;
  }

  /**
   * @return the name of filesystem
   */
  @Override
  public String getFileSystemName() {
    return "remote";
  }

  /**
   * Start the build.
   *
   * @param buildId the UUID build identifier
   * @param finalizeActions whether this build is finalizing actions so that the output service
   *                        can track output tree modifications
   *
   * @return a ModifiedFileSet of changed output files.
   *
   * @throws BuildFailedException if build preparation failed
   * @throws InterruptedException
   */
  @Override
  public ModifiedFileSet startBuild(EventHandler eventHandler, UUID buildId, boolean finalizeActions)
      throws BuildFailedException, AbruptExitException, InterruptedException {
    this.eventHandler = eventHandler;
    this.buildId = buildId;
    this.finalizeActions = finalizeActions;

    // i'm really not sure what we should be doing here, but for sure, I want to do better than this
    return ModifiedFileSet.EVERYTHING_MODIFIED;
  }

  /**
   * Finish the build.
   *
   * @param buildSuccessful iff build was successful
   * @throws BuildFailedException on failure
   */
  @Override
  public void finalizeBuild(boolean buildSuccessful)
      throws BuildFailedException, AbruptExitException, InterruptedException {
    // System.out.println("RemoteOutputService::finalizeBuild(" + (buildSuccessful ? "true" : "false") + ")");
  }

  /** Notify the output service of a completed action. */
  @Override
  public void finalizeAction(Action action, MetadataHandler metadataHandler)
      throws IOException, EnvironmentalExecException {
    // System.out.println("RemoteOutputService::finalizeAction(" + action + ")");
  }

  /**
   * @return the BatchStat instance or null.
   */
  @Override
  public BatchStat getBatchStatter() {
    return new RemoteBatchStat(execRootSupplier.get());
  }

  /**
   * @return true iff createSymlinkTree() is available.
   */
  @Override
  public boolean canCreateSymlinkTree() {
    return true;
  }

  /**
   * Creates the symlink tree
   *
   * @param inputPath the input manifest
   * @param outputPath the output manifest
   * @param filesetTree is true iff we're constructing a Fileset
   * @param symlinkTreeRoot the symlink tree root, relative to the execRoot
   * @throws ExecException on failure
   * @throws InterruptedException
   */
  @Override
  public void createSymlinkTree(Path inputPath, Path outputPath, boolean filesetTree,
      PathFragment symlinkTreeRoot) throws ExecException, InterruptedException {
    throw new UnsupportedOperationException();
  }

  /**
   * Cleans the entire output tree.
   *
   * @throws ExecException on failure
   * @throws InterruptedException
   */
  @Override
  public void clean() throws ExecException, InterruptedException {
    // throw new UnsupportedOperationException();
  }

  /** @return true iff the file actually lives on a remote server */
  @Override
  public boolean isRemoteFile(Artifact file) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean supportsActionFileSystem() {
    return true;
  }

  /**
   * @param sourceDelegate filesystem for reading source files (excludes output files)
   * @param execRootFragment absolute path fragment pointing to the execution root
   * @param relativeOutputPath execution root relative path to output
   * @param sourceRoots list of directories on the package path (from {@link
   *     com.google.devtools.build.lib.pkgcache.PathPackageLocator})
   * @param inputArtifactData information about required inputs to the action
   * @param allowedInputs optional inputs that might be added during input discovery
   * @param outputArtifacts required outputs of the action
   * @return an action-scoped filesystem if {@link supportsActionFileSystem} is true
   */
  @Override
  public FileSystem createActionFileSystem(
      FileSystem sourceDelegate,
      PathFragment execRootFragment,
      String relativeOutputPath,
      ImmutableList<Root> sourceRoots,
      ActionInputMap inputArtifactData,
      Iterable<Artifact> allowedInputs,
      Iterable<Artifact> outputArtifacts) {
    return new RemoteActionFileSystem(
        writeDelegate,
        sourceDelegate,
        execRootFragment,
        relativeOutputPath,
        sourceRoots,
        inputArtifactData,
        allowedInputs,
        outputArtifacts,
        locations);
  }

  /**
   * Updates the context used by the filesystem returned by {@link createActionFileSystem}.
   *
   * <p>Should be called as context changes throughout action execution.
   *
   * @param actionFileSystem must be a filesystem returned by {@link createActionFileSystem}.
   */
  @Override
  public void updateActionFileSystemContext(
      FileSystem actionFileSystem, SkyFunction.Environment env, MetadataConsumer consumer) {
    Preconditions.checkState(actionFileSystem instanceof RemoteActionFileSystem);
    RemoteActionFileSystem remoteActionFileSystem = (RemoteActionFileSystem) actionFileSystem;
    remoteActionFileSystem.updateContext(env, consumer);
  }

  private final class RemoteBatchStat implements BatchStat {

    private final Path execRoot;

    RemoteBatchStat(Path execRoot) {
      this.execRoot = execRoot;
    }

    /**
     *
     * @param includeDigest whether to include a file digest in the return values.
     * @param includeLinks whether to include a symlink stat in the return values.
     * @param paths The input paths to stat(), relative to the exec root.
     * @return an array list of FileStatusWithDigest in the same order as the input. May
     *         contain null values.
     * @throws IOException on unexpected failure.
     * @throws InterruptedException on interrupt.
     */
    public List<FileStatusWithDigest> batchStat(boolean includeDigest,
                                                boolean includeLinks,
                                                Iterable<PathFragment> paths)
        throws IOException, InterruptedException {
      // System.out.println(String.format("RemoteBatchStat::batchStat(%s, %s, %s)", includeDigest, includeLinks, paths));
      ImmutableList.Builder<FileStatusWithDigest> stats = new ImmutableList.Builder<>();
      for (PathFragment path : paths) {
        Symlinks symlinks = includeLinks ? Symlinks.NOFOLLOW : Symlinks.FOLLOW;
        FileStatus stat = execRoot.getRelative(path).stat(symlinks);
        FileStatusWithDigest statWithDigest;
        if (includeDigest) {
          // FIXME make it compute digest...
          statWithDigest = FileStatusWithDigestAdapter.adapt(stat);
        } else {
          statWithDigest = FileStatusWithDigestAdapter.adapt(stat);
        }
        stats.add(statWithDigest);
      }
      return stats.build();
    }
  }
}
