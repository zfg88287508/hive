/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.io.orc;

import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_DEFAULT_BLOCK_PADDING;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_DEFAULT_BLOCK_SIZE;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_DEFAULT_BUFFER_SIZE;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_DEFAULT_COMPRESS;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_DEFAULT_ROW_INDEX_STRIDE;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_DEFAULT_STRIPE_SIZE;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_ORC_WRITE_FORMAT;

import java.io.IOException;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.io.filters.BloomFilterIO;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;

/**
 * Contains factory methods to read or write ORC files.
 */
public final class OrcFile {

  public static final String MAGIC = "ORC";

  /**
   * Create a version number for the ORC file format, so that we can add
   * non-forward compatible changes in the future. To make it easier for users
   * to understand the version numbers, we use the Hive release number that
   * first wrote that version of ORC files.
   *
   * Thus, if you add new encodings or other non-forward compatible changes
   * to ORC files, which prevent the old reader from reading the new format,
   * you should change these variable to reflect the next Hive release number.
   * Non-forward compatible changes should never be added in patch releases.
   *
   * Do not make any changes that break backwards compatibility, which would
   * prevent the new reader from reading ORC files generated by any released
   * version of Hive.
   */
  public static enum Version {
    V_0_11("0.11", 0, 11),
      V_0_12("0.12", 0, 12);

    public static final Version CURRENT = V_0_12;

    private final String name;
    private final int major;
    private final int minor;

    private Version(String name, int major, int minor) {
      this.name = name;
      this.major = major;
      this.minor = minor;
    }

    public static Version byName(String name) {
      for(Version version: values()) {
        if (version.name.equals(name)) {
          return version;
        }
      }
      throw new IllegalArgumentException("Unknown ORC version " + name);
    }

    /**
     * Get the human readable name for the version.
     */
    public String getName() {
      return name;
    }

    /**
     * Get the major version number.
     */
    public int getMajor() {
      return major;
    }

    /**
     * Get the minor version number.
     */
    public int getMinor() {
      return minor;
    }
  }

  /**
   * Records the version of the writer in terms of which bugs have been fixed.
   * For bugs in the writer, but the old readers already read the new data
   * correctly, bump this version instead of the Version.
   */
  public static enum WriterVersion {
    ORIGINAL(0),
    HIVE_8732(1), // corrupted stripe/file maximum column statistics
    HIVE_4243(2), // use real column names from Hive tables (Hive 2.x only)
    HIVE_12055(3), // vectorized writer (Hive 2.x only)
    HIVE_13083(4); // decimal writer updating present stream wrongly

    private final int id;

    public int getId() {
      return id;
    }

    private WriterVersion(int id) {
      this.id = id;
    }
  }

  public static enum EncodingStrategy {
    SPEED, COMPRESSION;
  }

  public static enum CompressionStrategy {
    SPEED, COMPRESSION;
  }

  // Note : these string definitions for table properties are deprecated,
  // and retained only for backward compatibility, please do not add to
  // them, add to OrcTableProperties below instead
  @Deprecated public static final String COMPRESSION = "orc.compress";
  @Deprecated public static final String COMPRESSION_BLOCK_SIZE = "orc.compress.size";
  @Deprecated public static final String STRIPE_SIZE = "orc.stripe.size";
  @Deprecated public static final String ROW_INDEX_STRIDE = "orc.row.index.stride";
  @Deprecated public static final String ENABLE_INDEXES = "orc.create.index";
  @Deprecated public static final String BLOCK_PADDING = "orc.block.padding";

  /**
   * Enum container for all orc table properties.
   * If introducing a new orc-specific table property,
   * add it here.
   */
  public static enum OrcTableProperties {
    COMPRESSION("orc.compress"),
    COMPRESSION_BLOCK_SIZE("orc.compress.size"),
    STRIPE_SIZE("orc.stripe.size"),
    BLOCK_SIZE("orc.block.size"),
    ROW_INDEX_STRIDE("orc.row.index.stride"),
    ENABLE_INDEXES("orc.create.index"),
    BLOCK_PADDING("orc.block.padding"),
    ENCODING_STRATEGY("orc.encoding.strategy"),
    BLOOM_FILTER_COLUMNS("orc.bloom.filter.columns"),
    BLOOM_FILTER_FPP("orc.bloom.filter.fpp");

    private final String propName;

    OrcTableProperties(String propName) {
      this.propName = propName;
    }

    public String getPropName(){
      return this.propName;
    }
  }

  // unused
  private OrcFile() {}

  /**
   * Create an ORC file reader.
   * @param fs file system
   * @param path file name to read from
   * @return a new ORC file reader.
   * @throws IOException
   */
  public static Reader createReader(FileSystem fs, Path path
  ) throws IOException {
    ReaderOptions opts = new ReaderOptions(new Configuration());
    opts.filesystem(fs);
    return new ReaderImpl(path, opts);
  }

  public static class ReaderOptions {
    private final Configuration conf;
    private FileSystem filesystem;
    private ReaderImpl.FileMetaInfo fileMetaInfo;
    private long maxLength = Long.MAX_VALUE;

    public ReaderOptions(Configuration conf) {
      this.conf = conf;
    }
    ReaderOptions fileMetaInfo(ReaderImpl.FileMetaInfo info) {
      fileMetaInfo = info;
      return this;
    }

    public ReaderOptions filesystem(FileSystem fs) {
      this.filesystem = fs;
      return this;
    }

    public ReaderOptions maxLength(long val) {
      maxLength = val;
      return this;
    }

    Configuration getConfiguration() {
      return conf;
    }

    FileSystem getFilesystem() {
      return filesystem;
    }

    ReaderImpl.FileMetaInfo getFileMetaInfo() {
      return fileMetaInfo;
    }

    long getMaxLength() {
      return maxLength;
    }
  }

  public static ReaderOptions readerOptions(Configuration conf) {
    return new ReaderOptions(conf);
  }

  public static Reader createReader(Path path,
                                    ReaderOptions options) throws IOException {
    return new ReaderImpl(path, options);
  }

  public static interface WriterContext {
    Writer getWriter();
  }

  public static interface WriterCallback {
    public void preStripeWrite(WriterContext context) throws IOException;
    public void preFooterWrite(WriterContext context) throws IOException;
  }

  /**
   * Options for creating ORC file writers.
   */
  public static class WriterOptions {
    private final Configuration configuration;
    private FileSystem fileSystemValue = null;
    private ObjectInspector inspectorValue = null;
    private long stripeSizeValue;
    private long blockSizeValue;
    private int rowIndexStrideValue;
    private int bufferSizeValue;
    private boolean blockPaddingValue;
    private CompressionKind compressValue;
    private MemoryManager memoryManagerValue;
    private Version versionValue;
    private WriterCallback callback;
    private EncodingStrategy encodingStrategy;
    private CompressionStrategy compressionStrategy;
    private float paddingTolerance;
    private String bloomFilterColumns;
    private double bloomFilterFpp;
    private boolean enforceBufferSize = false;

    WriterOptions(Properties tableProperties, Configuration conf) {
      configuration = conf;
      memoryManagerValue = getMemoryManager(conf);

      String propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.STRIPE_SIZE.propName);
      stripeSizeValue = propValue == null ? HiveConf.getLongVar(conf, HIVE_ORC_DEFAULT_STRIPE_SIZE)
          : Long.parseLong(propValue);

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.BLOCK_SIZE.propName);
      blockSizeValue = propValue == null ? HiveConf.getLongVar(conf, HIVE_ORC_DEFAULT_BLOCK_SIZE)
          : Long.parseLong(propValue);

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.ROW_INDEX_STRIDE.propName);
      rowIndexStrideValue = propValue == null ? HiveConf.getIntVar(conf, HIVE_ORC_DEFAULT_ROW_INDEX_STRIDE)
          : Integer.parseInt(propValue);

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.ENABLE_INDEXES.propName);
      if (propValue != null && !Boolean.parseBoolean(propValue)) {
        rowIndexStrideValue = 0;
      }

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.COMPRESSION_BLOCK_SIZE.propName);
      bufferSizeValue = propValue == null ? HiveConf.getIntVar(conf, HIVE_ORC_DEFAULT_BUFFER_SIZE)
          : Integer.parseInt(propValue);

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.BLOCK_PADDING.propName);
      blockPaddingValue = propValue == null ? HiveConf.getBoolVar(conf, HIVE_ORC_DEFAULT_BLOCK_PADDING)
          : Boolean.parseBoolean(propValue);

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.COMPRESSION.propName);
      compressValue = propValue == null ? CompressionKind.valueOf(HiveConf.getVar(conf, HIVE_ORC_DEFAULT_COMPRESS))
          : CompressionKind.valueOf(propValue.toUpperCase());

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.BLOOM_FILTER_COLUMNS.propName);
      bloomFilterColumns = propValue;

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.BLOOM_FILTER_FPP.propName);
      bloomFilterFpp = propValue == null ? BloomFilterIO.DEFAULT_FPP : Double.parseDouble(propValue);

      String versionName = HiveConf.getVar(conf, HIVE_ORC_WRITE_FORMAT);
      if (versionName == null) {
        versionValue = Version.CURRENT;
      } else {
        versionValue = Version.byName(versionName);
      }

      propValue = tableProperties == null ? null
          : tableProperties.getProperty(OrcTableProperties.ENCODING_STRATEGY.propName);
      if (propValue == null) {
        propValue = conf.get(HiveConf.ConfVars.HIVE_ORC_ENCODING_STRATEGY.varname);
      }
      encodingStrategy = propValue == null ? EncodingStrategy.SPEED : EncodingStrategy.valueOf(propValue);

      String compString = conf.get(HiveConf.ConfVars.HIVE_ORC_COMPRESSION_STRATEGY.varname);
      if (compString == null) {
        compressionStrategy = CompressionStrategy.SPEED;
      } else {
        compressionStrategy = CompressionStrategy.valueOf(compString);
      }

      paddingTolerance = conf.getFloat(HiveConf.ConfVars.HIVE_ORC_BLOCK_PADDING_TOLERANCE.varname,
          HiveConf.ConfVars.HIVE_ORC_BLOCK_PADDING_TOLERANCE.defaultFloatVal);
    }

    /**
     * Provide the filesystem for the path, if the client has it available.
     * If it is not provided, it will be found from the path.
     */
    public WriterOptions fileSystem(FileSystem value) {
      fileSystemValue = value;
      return this;
    }

    /**
     * Set the stripe size for the file. The writer stores the contents of the
     * stripe in memory until this memory limit is reached and the stripe
     * is flushed to the HDFS file and the next stripe started.
     */
    public WriterOptions stripeSize(long value) {
      stripeSizeValue = value;
      return this;
    }

    /**
     * Set the file system block size for the file. For optimal performance,
     * set the block size to be multiple factors of stripe size.
     */
    public WriterOptions blockSize(long value) {
      blockSizeValue = value;
      return this;
    }

    /**
     * Set the distance between entries in the row index. The minimum value is
     * 1000 to prevent the index from overwhelming the data. If the stride is
     * set to 0, no indexes will be included in the file.
     */
    public WriterOptions rowIndexStride(int value) {
      rowIndexStrideValue = value;
      return this;
    }

    /**
     * The size of the memory buffers used for compressing and storing the
     * stripe in memory.
     */
    public WriterOptions bufferSize(int value) {
      bufferSizeValue = value;
      return this;
    }

    /**
     * Enforce writer to use requested buffer size instead of estimating
     * buffer size based on stripe size and number of columns.
     * See bufferSize() method for more info.
     * Default: false
     */
    public WriterOptions enforceBufferSize() {
      enforceBufferSize = true;
      return this;
    }

    /**
     * Sets whether the HDFS blocks are padded to prevent stripes from
     * straddling blocks. Padding improves locality and thus the speed of
     * reading, but costs space.
     */
    public WriterOptions blockPadding(boolean value) {
      blockPaddingValue = value;
      return this;
    }

    /**
     * Sets the encoding strategy that is used to encode the data.
     */
    public WriterOptions encodingStrategy(EncodingStrategy strategy) {
      encodingStrategy = strategy;
      return this;
    }

    /**
     * Sets the tolerance for block padding as a percentage of stripe size.
     */
    public WriterOptions paddingTolerance(float value) {
      paddingTolerance = value;
      return this;
    }

    /**
     * Comma separated values of column names for which bloom filter is to be created.
     */
    public WriterOptions bloomFilterColumns(String columns) {
      bloomFilterColumns = columns;
      return this;
    }

    /**
     * Specify the false positive probability for bloom filter.
     * @param fpp - false positive probability
     * @return
     */
    public WriterOptions bloomFilterFpp(double fpp) {
      bloomFilterFpp = fpp;
      return this;
    }

    /**
     * Sets the generic compression that is used to compress the data.
     */
    public WriterOptions compress(CompressionKind value) {
      compressValue = value;
      return this;
    }

    /**
     * A required option that sets the object inspector for the rows. Used
     * to determine the schema for the file.
     */
    public WriterOptions inspector(ObjectInspector value) {
      inspectorValue = value;
      return this;
    }

    /**
     * Sets the version of the file that will be written.
     */
    public WriterOptions version(Version value) {
      versionValue = value;
      return this;
    }

    /**
     * Add a listener for when the stripe and file are about to be closed.
     * @param callback the object to be called when the stripe is closed
     * @return
     */
    public WriterOptions callback(WriterCallback callback) {
      this.callback = callback;
      return this;
    }

    /**
     * A package local option to set the memory manager.
     */
    WriterOptions memory(MemoryManager value) {
      memoryManagerValue = value;
      return this;
    }

  }

  /**
   * Create a default set of write options that can be modified.
   */
  public static WriterOptions writerOptions(Configuration conf) {
    return new WriterOptions(null, conf);
  }

  /**
   * Create a set of write options based on a set of table properties and
   * configuration. Properties takes precedence over configuration.
   * @param tableProperties the properties of the table
   * @param conf the configuration of the query
   * @return a WriterOptions object that can be modified
   */
  public static WriterOptions writerOptions(Properties tableProperties,
      Configuration conf) {
    return new WriterOptions(tableProperties, conf);
  }

  /**
   * Create an ORC file writer. This is the public interface for creating
   * writers going forward and new options will only be added to this method.
   * @param path filename to write to
   * @param opts the options
   * @return a new ORC file writer
   * @throws IOException
   */
  public static Writer createWriter(Path path,
                                    WriterOptions opts
                                    ) throws IOException {
    FileSystem fs = opts.fileSystemValue == null ?
      path.getFileSystem(opts.configuration) : opts.fileSystemValue;

    return new WriterImpl(fs, path, opts.configuration, opts.inspectorValue,
                          opts.stripeSizeValue, opts.compressValue,
                          opts.bufferSizeValue, opts.rowIndexStrideValue,
                          opts.memoryManagerValue, opts.blockPaddingValue,
                          opts.versionValue, opts.callback,
                          opts.encodingStrategy, opts.compressionStrategy,
                          opts.paddingTolerance, opts.blockSizeValue,
                          opts.bloomFilterColumns, opts.bloomFilterFpp,
                          opts.enforceBufferSize);
  }

  /**
   * Create an ORC file writer. This method is provided for API backward
   * compatability with Hive 0.11.
   * @param fs file system
   * @param path filename to write to
   * @param inspector the ObjectInspector that inspects the rows
   * @param stripeSize the number of bytes in a stripe
   * @param compress how to compress the file
   * @param bufferSize the number of bytes to compress at once
   * @param rowIndexStride the number of rows between row index entries or
   *                       0 to suppress all indexes
   * @return a new ORC file writer
   * @throws IOException
   */
  public static Writer createWriter(FileSystem fs,
                                    Path path,
                                    Configuration conf,
                                    ObjectInspector inspector,
                                    long stripeSize,
                                    CompressionKind compress,
                                    int bufferSize,
                                    int rowIndexStride) throws IOException {
    return createWriter(path,
                        writerOptions(conf)
                        .fileSystem(fs)
                        .inspector(inspector)
                        .stripeSize(stripeSize)
                        .compress(compress)
                        .bufferSize(bufferSize)
                        .rowIndexStride(rowIndexStride));
  }

  private static ThreadLocal<MemoryManager> memoryManager = null;

  private static synchronized MemoryManager getMemoryManager(
      final Configuration conf) {
    if (memoryManager == null) {
      memoryManager = new ThreadLocal<MemoryManager>() {
        @Override
        protected MemoryManager initialValue() {
          return new MemoryManager(conf);
        }
      };
    }
    return memoryManager.get();
  }

}
