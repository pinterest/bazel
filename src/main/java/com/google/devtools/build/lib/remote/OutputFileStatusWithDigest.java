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

import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.OutputFile;

import javax.annotation.Nullable;

/**
 * A FileStatusWithDigest representing an OutputFile.
 */
class OutputFileStatusWithDigest implements FileStatusWithDigest {
  private final boolean isExecutable;
  private final long size;
  private final byte[] digest;

  OutputFileStatusWithDigest(OutputFile outputFile) {
    isExecutable = outputFile.getIsExecutable();
    // FIXME symlink support
    size = outputFile.getDigest().getSizeBytes();
    digest = parseDigest(outputFile.getDigest());
  }

  public static byte[] parseDigest(Digest digest) {
    return BaseEncoding.base16().lowerCase().decode(digest.getHash());
  }

  /**
   * Returns true iff this file is a regular file or {@code isSpecial()}.
   */
  public boolean isFile() {
    return true;
  }

  /**
   * Returns true iff this file is executable.
   */
  public boolean isExecutable() {
    return isExecutable;
  }

  /**
   * Returns true iff this file is a directory.
   */
  public boolean isDirectory() {
    return false;
  }

  /**
   * Returns true iff this file is a symbolic link.
   */
  public boolean isSymbolicLink() {
    return false;
  }

  /**
   * Returns true iff this file is a special file (e.g. socket, fifo or device). {@link #getSize()}
   * can't be trusted for such files.
   */
  public boolean isSpecialFile() {
    return false;
  }

  /**
   * Returns the total size, in bytes, of this file.
   */
  public long getSize() {
    return size;
  }

  /**
   * Returns the last modified time of this file's data (milliseconds since
   * UNIX epoch).
   *
   * TODO(bazel-team): Unix actually gives us nanosecond resolution for mtime and ctime. Consider
   * making use of this.
   */
  public long getLastModifiedTime() {
    return 0;
  }

  /**
   * Returns the last change time of this file, where change means any change
   * to the file, including metadata changes (milliseconds since UNIX epoch).
   */
  public long getLastChangeTime() {
    return 0;
  }

  /**
   * Returns the unique file node id. Usually it is computed using both device
   * and inode numbers.
   *
   * <p>Think of this value as a reference to the underlying inode. "mv"ing file a to file b
   * ought to cause the node ID of b to change, but appending / modifying b should not.
   */
  public long getNodeId() {
    return 0;
  }

  /**
   * @return the digest of the file.
   */
  @Override
  public byte[] getDigest() {
    return digest;
  }
}
