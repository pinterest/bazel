package com.google.devtools.build.lib.rules.cpp;

import java.io.File;
import java.io.FileOutputStream;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;

import java.nio.file.Path;
import java.nio.file.Paths;

import static java.lang.Math.max;

public final class ClangHeaderMap {
  // Logical representation of a bucket.
  // The actual data is stored in the string pool.
  private class HMapBucket {
    String key;
    String prefix;
    String suffix;

    HMapBucket(String key, String prefix, String suffix) {
      this.key = key;
      this.prefix = prefix;
      this.suffix = suffix;
    }
  }

  private static final int HEADER_MAGIC = ('h' << 24) | ('m' << 16) | ('a' << 8) | 'p';
  private static final short HEADER_VERSION = 1;
  private static final short HEADER_RESERVED = 0;
  private static final int EMPTY_BUCKET_KEY = 0;

  private static final int HEADER_SIZE = 24;
  private static final int BUCKET_SIZE = 12;

  private static final int INT_SIZE = Integer.SIZE/8;

  // Data stored in accordance to Clang's lexer types
  /**
  enum {
      HMAP_HeaderMagicNumber = ('h' << 24) | ('m' << 16) | ('a' << 8) | 'p',
      HMAP_HeaderVersion = 1,
      HMAP_EmptyBucketKey = 0
    };

    struct HMapBucket {
      uint32_t Key;    // Offset (into strings) of key.
      uint32_t Prefix; // Offset (into strings) of value prefix.
      uint32_t Suffix; // Offset (into strings) of value suffix.
    };

    struct HMapHeader {
      uint32_t Magic;      // Magic word, also indicates byte order.
      uint16_t Version;      // Version number -- currently 1.
      uint16_t Reserved;     // Reserved for future use - zero for now.
      uint32_t StringsOffset;  // Offset to start of string pool.
      uint32_t NumEntries;     // Number of entries in the string table.
      uint32_t NumBuckets;     // Number of buckets (always a power of 2).
      uint32_t MaxValueLength; // Length of longest result path (excluding nul).
      // An array of 'NumBuckets' HMapBucket objects follows this header.
      // Strings follow the buckets, at StringsOffset.
    };
  */
  public ByteBuffer buff;

  private int numBuckets;
  private int stringsOffset;
  private int stringsSize;
  private int maxValueLength;
  private int maxStringsSize;

  // Used only for creation
  private HMapBucket[] buckets;

  // Create a headermap from a raw Map of keys to strings
  // Usage:
  // A given path to a header is keyed by that header.
  // .e. Header.h -> Path/To/Header.h
  //
  // Additionally, it is possible to alias custom paths to headers.
  // For example, it is possible to namespace a given target
  // i.e. MyTarget/Header.h -> Path/To/Header.h
  //
  // The HeaderMap format is defined by the lexer of Clang
  // https://clang.llvm.org/doxygen/HeaderMap_8cpp_source.html
  ClangHeaderMap(Map<String, String> headerPathsByKeys) {
    int dataOffset = 1;
    System.out.println(headerPathsByKeys);
    setMap(headerPathsByKeys);

    int endBuckets = HEADER_SIZE + numBuckets * BUCKET_SIZE;
    stringsOffset = endBuckets - dataOffset;
    int totalBufferSize = endBuckets + maxStringsSize;
    buff = ByteBuffer.wrap(new byte[totalBufferSize]).order(ByteOrder.LITTLE_ENDIAN);

    // Write out the header
    buff.putInt(HEADER_MAGIC);
    buff.putShort(HEADER_VERSION);
    buff.putShort(HEADER_RESERVED);
    buff.putInt(stringsOffset);

    // For each entry, we write a key, suffix, and prefix
    int stringPoolSize = headerPathsByKeys.size() * 3;
    buff.putInt(stringPoolSize);
    buff.putInt(numBuckets);
    buff.putInt(maxValueLength);

    // Write out buckets and compute string offsets
    byte[] stringBytes = new byte[maxStringsSize];

    // Used to compute the current offset
    stringsSize = 0;
    for (int i = 0; i < numBuckets; i++) {
      HMapBucket bucket = buckets[i];
      if (bucket == null) {
        buff.putInt(EMPTY_BUCKET_KEY);
        buff.putInt(0);
        buff.putInt(0);
      } else {
        int keyOffset = stringsSize;
        buff.putInt(keyOffset + dataOffset);
        stringsSize = addString(bucket.key, stringsSize, stringBytes);

        int prefixOffset = stringsSize;
        stringsSize = addString(bucket.prefix, stringsSize, stringBytes);
        buff.putInt(prefixOffset + dataOffset);

        int suffixOffset = stringsSize;
        stringsSize = addString(bucket.suffix, stringsSize, stringBytes);
        buff.putInt(suffixOffset + dataOffset);
      }
    }
    buff.put(stringBytes, 0, stringsSize);
  }

  // For testing purposes. Implement a similiar algorithm as the clang
  // lexer.
  public String get(String key) {
    int bucketIdx = clangKeyHash(key) & (numBuckets - 1);
    while (bucketIdx < numBuckets) {
      // Buckets are right after the header
      int bucketOffset = HEADER_SIZE + (BUCKET_SIZE * bucketIdx);
      int keyOffset = buff.getInt(bucketOffset);

      // Note: the lexer does a case insensitive compare here but
      // it isn't necessary for test purposes
      if (key.equals(getString(keyOffset)) == false) {
        bucketIdx++;
        continue;
      }

      // Start reading bytes from the prefix
      int prefixOffset = buff.getInt(bucketOffset + INT_SIZE);
      int suffixOffset = buff.getInt(bucketOffset + INT_SIZE * 2);
      return getString(prefixOffset) + getString(suffixOffset);
    }
    return null;
  }

  // Return a string from an offset
  // This method is used for testing only.
  private String getString(int offset) {
    int readOffset = stringsOffset + offset;
    int endStringsOffset = stringsOffset + stringsSize;
    int idx = readOffset;
    byte[] stringBytes = new byte[2048];
    while(idx < endStringsOffset) {
      byte c = (byte)buff.getChar(idx);
      if (c == 0) {
        break;
      }
      stringBytes[idx] = c;
      idx++;
    }
    try {
      return new String(stringBytes).trim();
    } catch(Exception e) {
      return null;
    }
  }

  private void addBucket(HMapBucket bucket) {
    String key = bucket.key;
    int bucketIdx = clangKeyHash(key) & (numBuckets - 1);

    // Base case, the bucket Idx is free
    if (buckets[bucketIdx] == null) {
      buckets[bucketIdx] = bucket;
      return;
    }

    // Handle collisions.

    // Try to find the next slot.
    //
    // The lexer does a linear scan of the hash table when keys do
    // not match, starting at the bucket.
    while(bucketIdx < numBuckets) {
      if (buckets[bucketIdx] == null) {
        buckets[bucketIdx] = bucket;
        return;
      }
      bucketIdx = (bucketIdx + 1) & (numBuckets - 1);
    }

    // If there are no more slots left, grow by a power of 2
    int newNumBuckets = numBuckets * 2;
    HMapBucket[] newBuckets = new HMapBucket[newNumBuckets];
    for(int i = 0; i < numBuckets; i++) {
      HMapBucket cpBucket = buckets[i];
      if (cpBucket != null) {
        int cpBucketIdx = clangKeyHash(cpBucket.key) & (newNumBuckets - 1);
        newBuckets[cpBucketIdx] = cpBucket;
      }
    }

    buckets = newBuckets;
    numBuckets = newNumBuckets;

    // Start again
    addBucket(bucket);
  }

  private void setMap(Map<String, String> headerPathsByKeys){
    // Compute header metadata
    maxValueLength = 1;
    maxStringsSize = 0;

    // Per the format, buckets need to be powers of 2 in size
    numBuckets = getNextPowerOf2(headerPathsByKeys.size());
    buckets = new HMapBucket[numBuckets];

    for(Map.Entry<String, String> entry: headerPathsByKeys.entrySet()){
      String key = entry.getKey();
      String path = entry.getValue();

      // Get the prefix and suffix
      String suffix;
      String prefix;
      Path pathValue = Paths.get(path);
      if (pathValue.getNameCount() < 2) {
        // The suffix is empty when the file path just a filename
        prefix = "";
        suffix = pathValue.getFileName().toString();
      } else {
        prefix = pathValue.getParent().toString() + "/";
        suffix = pathValue.getFileName().toString();
      }

      HMapBucket bucket = new HMapBucket(key, prefix, suffix);
      addBucket(bucket);
      int prefixLen = prefix.getBytes().length + 1;
      int suffixLen = suffix.getBytes().length + 1;
      int keyLen = key.getBytes().length + 1;
      maxStringsSize += prefixLen + suffixLen + keyLen;

      maxValueLength = max(maxValueLength, keyLen);
      maxValueLength = max(maxValueLength, suffixLen);
      maxValueLength = max(maxValueLength, prefixLen);
    }
  }

  // Utils

  private static int addString(String str, int totalLength, byte[] stringBytes) {
    for (byte b : str.getBytes(StandardCharsets.UTF_8)) {
      stringBytes[totalLength] = b;
      totalLength++;
    }
    stringBytes[totalLength] = (byte) 0;
    totalLength++;
    return totalLength;
  }

  private static int getNextPowerOf2(int a) {
    int b = 1;
    while (b < a) {
      b = b << 1;
    }
    return b;
  }

  // The same hashing algorithm as the Lexer.
  // Buckets must be inserted according to this.
  private static int clangKeyHash(String key) {
    // Keys are case insensitve.
    String lowerCaseKey = toLowerCaseAscii(key);
    int hash = 0;
    for (byte c : lowerCaseKey.getBytes(StandardCharsets.UTF_8)) {
      hash += c * 13;
    }
    return hash;
  }

  // Utils from Guava ( FIXME: use those utils )
  public static String toLowerCaseAscii(String string) {
    int length = string.length();
    StringBuilder builder = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      builder.append(toLowerCaseAscii(string.charAt(i)));
    }
    return builder.toString();
  }

  public static char toLowerCaseAscii(char c) {
    return isUpperCase(c) ? (char) (c ^ 0x20) : c;
  }

  public static boolean isUpperCase(char c) {
    return (c >= 'A') && (c <= 'Z');
  }
}

