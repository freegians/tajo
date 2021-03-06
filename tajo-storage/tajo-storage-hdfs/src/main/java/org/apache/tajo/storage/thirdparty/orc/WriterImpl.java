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

package org.apache.tajo.storage.thirdparty.orc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.util.JavaDataModel;
import org.apache.hadoop.io.Text;
import org.apache.orc.*;
import org.apache.orc.CompressionCodec.Modifier;
import org.apache.orc.OrcProto.RowIndexEntry;
import org.apache.orc.OrcUtils;
import org.apache.orc.impl.*;
import org.apache.tajo.datum.Datum;
import org.apache.tajo.datum.Inet4Datum;
import org.apache.tajo.datum.Int4Datum;
import org.apache.tajo.datum.Int8Datum;
import org.apache.tajo.storage.Tuple;
import org.apache.tajo.storage.thirdparty.orc.OrcFile.*;
import org.apache.tajo.util.datetime.DateTimeConstants;
import org.apache.tajo.util.datetime.DateTimeUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * An ORC file writer. The file is divided into stripes, which is the natural
 * unit of work when reading. Each stripe is buffered in memory until the
 * memory reaches the stripe size and then it is written out broken down by
 * columns. Each column is written by a TreeWriter that is specific to that
 * type of column. TreeWriters may have children TreeWriters that handle the
 * sub-types. Each of the TreeWriters writes the column's data as a set of
 * streams.
 *
 * This class is unsynchronized like most Stream objects, so from the creation of an OrcFile and all
 * access to a single instance has to be from a single thread.
 *
 * There are no known cases where these happen between different threads today.
 *
 * Caveat: the MemoryManager is created during WriterOptions create, that has to be confined to a single
 * thread as well.
 *
 */
public class WriterImpl implements Writer, MemoryManager.Callback {

  private static final Log LOG = LogFactory.getLog(WriterImpl.class);

  private static final int HDFS_BUFFER_SIZE = 256 * 1024;
  private static final int MIN_ROW_INDEX_STRIDE = 1000;

  // threshold above which buffer size will be automatically resized
  private static final int COLUMN_COUNT_THRESHOLD = 1000;

  private final FileSystem fs;
  private final Path path;
  private final long defaultStripeSize;
  private long adjustedStripeSize;
  private final int rowIndexStride;
  private final CompressionKind compress;
  private final CompressionCodec codec;
  private final boolean addBlockPadding;
  private final int bufferSize;
  private final long blockSize;
  private final double paddingTolerance;
  private final TypeDescription schema;

  // the streams that make up the current stripe
  private final Map<StreamName, BufferedStream> streams = new TreeMap<>();

  private FSDataOutputStream rawWriter = null;
  // the compressed metadata information outStream
  private OutStream writer = null;
  // a protobuf outStream around streamFactory
  private CodedOutputStream protobufWriter = null;
  private long headerLength;
  private int columnCount;
  private long rowCount = 0;
  private long rowsInStripe = 0;
  private long rawDataSize = 0;
  private int rowsInIndex = 0;
  private int stripesAtLastFlush = -1;
  private final List<OrcProto.StripeInformation> stripes = new ArrayList<>();
  private final Map<String, ByteString> userMetadata = new TreeMap<>();
  private final StreamFactory streamFactory = new StreamFactory();
  private final TreeWriter treeWriter;
  private final boolean buildIndex;
  private final MemoryManager memoryManager;
  private final Version version;
  private final Configuration conf;
  private final WriterCallback callback;
  private final WriterContext callbackContext;
  private final EncodingStrategy encodingStrategy;
  private final CompressionStrategy compressionStrategy;
  private final boolean[] bloomFilterColumns;
  private final double bloomFilterFpp;
  private boolean writeTimeZone;
  private TimeZone timeZone;

  public WriterImpl(FileSystem fs,
                    Path path,
                    OrcFile.WriterOptions opts,
                    TimeZone timeZone) throws IOException {
    this.fs = fs;
    this.path = path;
    this.conf = opts.getConfiguration();
    this.callback = opts.getCallback();
    this.schema = opts.getSchema();
    if (callback != null) {
      callbackContext = new OrcFile.WriterContext(){

        @Override
        public Writer getWriter() {
          return WriterImpl.this;
        }
      };
    } else {
      callbackContext = null;
    }
    this.adjustedStripeSize = opts.getStripeSize();
    this.defaultStripeSize = opts.getStripeSize();
    this.version = opts.getVersion();
    this.encodingStrategy = opts.getEncodingStrategy();
    this.compressionStrategy = opts.getCompressionStrategy();
    this.addBlockPadding = opts.getBlockPadding();
    this.blockSize = opts.getBlockSize();
    this.paddingTolerance = opts.getPaddingTolerance();
    this.compress = opts.getCompress();
    this.rowIndexStride = opts.getRowIndexStride();
    this.memoryManager = opts.getMemoryManager();
    buildIndex = rowIndexStride > 0;
    codec = createCodec(compress);
    int numColumns = schema.getMaximumId() + 1;
    this.bufferSize = getEstimatedBufferSize(defaultStripeSize,
        numColumns, opts.getBufferSize());
    if (version == OrcFile.Version.V_0_11) {
      /* do not write bloom filters for ORC v11 */
      this.bloomFilterColumns = new boolean[schema.getMaximumId() + 1];
    } else {
      this.bloomFilterColumns =
          OrcUtils.includeColumns(opts.getBloomFilterColumns(), schema);
    }
    this.bloomFilterFpp = opts.getBloomFilterFpp();
    this.timeZone = timeZone;
    treeWriter = createTreeWriter(schema, streamFactory, false);
    if (buildIndex && rowIndexStride < MIN_ROW_INDEX_STRIDE) {
      throw new IllegalArgumentException("Row stride must be at least " +
          MIN_ROW_INDEX_STRIDE);
    }

    // ensure that we are able to handle callbacks before we register ourselves
    memoryManager.addWriter(path, opts.getStripeSize(), this);
  }

  @VisibleForTesting
  public static int getEstimatedBufferSize(long stripeSize, int numColumns,
                                           int bs) {
    // The worst case is that there are 2 big streams per a column and
    // we want to guarantee that each stream gets ~10 buffers.
    // This keeps buffers small enough that we don't get really small stripe
    // sizes.
    int estBufferSize = (int) (stripeSize / (20 * numColumns));
    estBufferSize = getClosestBufferSize(estBufferSize);
    if (estBufferSize > bs) {
      estBufferSize = bs;
    } else {
      LOG.info("WIDE TABLE - Number of columns: " + numColumns +
          " Chosen compression buffer size: " + estBufferSize);
    }
    return estBufferSize;
  }

  private static int getClosestBufferSize(int estBufferSize) {
    final int kb4 = 4 * 1024;
    final int kb8 = 8 * 1024;
    final int kb16 = 16 * 1024;
    final int kb32 = 32 * 1024;
    final int kb64 = 64 * 1024;
    final int kb128 = 128 * 1024;
    final int kb256 = 256 * 1024;
    if (estBufferSize <= kb4) {
      return kb4;
    } else if (estBufferSize > kb4 && estBufferSize <= kb8) {
      return kb8;
    } else if (estBufferSize > kb8 && estBufferSize <= kb16) {
      return kb16;
    } else if (estBufferSize > kb16 && estBufferSize <= kb32) {
      return kb32;
    } else if (estBufferSize > kb32 && estBufferSize <= kb64) {
      return kb64;
    } else if (estBufferSize > kb64 && estBufferSize <= kb128) {
      return kb128;
    } else {
      return kb256;
    }
  }

  // the assumption is only one ORC writer open at a time, which holds true for
  // most of the cases. HIVE-6455 forces single writer case.
  private long getMemoryAvailableForORC() {
    OrcConf.ConfVars poolVar = OrcConf.ConfVars.HIVE_ORC_FILE_MEMORY_POOL;
    double maxLoad = conf.getFloat(poolVar.varname, poolVar.defaultFloatVal);
    long totalMemoryPool = Math.round(ManagementFactory.getMemoryMXBean().
        getHeapMemoryUsage().getMax() * maxLoad);
    return totalMemoryPool;
  }

  public static CompressionCodec createCodec(CompressionKind kind) {
    switch (kind) {
      case NONE:
        return null;
      case ZLIB:
        return new ZlibCodec();
      case SNAPPY:
        return new SnappyCodec();
      case LZO:
        try {
          Class<? extends CompressionCodec> lzo =
              (Class<? extends CompressionCodec>)
                  Class.forName("org.apache.hadoop.hive.ql.io.orc.LzoCodec");
          return lzo.newInstance();
        } catch (ClassNotFoundException e) {
          throw new IllegalArgumentException("LZO is not available.", e);
        } catch (InstantiationException e) {
          throw new IllegalArgumentException("Problem initializing LZO", e);
        } catch (IllegalAccessException e) {
          throw new IllegalArgumentException("Insufficient access to LZO", e);
        }
      default:
        throw new IllegalArgumentException("Unknown compression codec: " +
            kind);
    }
  }

  @Override
  public boolean checkMemory(double newScale) throws IOException {
    long limit = (long) Math.round(adjustedStripeSize * newScale);
    long size = estimateStripeSize();
    if (LOG.isDebugEnabled()) {
      LOG.debug("ORC writer " + path + " size = " + size + " limit = " +
                limit);
    }
    if (size > limit) {
      flushStripe();
      return true;
    }
    return false;
  }

  /**
   * This class is used to hold the contents of streams as they are buffered.
   * The TreeWriters write to the outStream and the codec compresses the
   * data as buffers fill up and stores them in the output list. When the
   * stripe is being written, the whole stream is written to the file.
   */
  private class BufferedStream implements OutStream.OutputReceiver {
    private final OutStream outStream;
    private final List<ByteBuffer> output = new ArrayList<>();

    BufferedStream(String name, int bufferSize,
                   CompressionCodec codec) throws IOException {
      outStream = new OutStream(name, bufferSize, codec, this);
    }

    /**
     * Receive a buffer from the compression codec.
     * @param buffer the buffer to save
     * @throws IOException
     */
    @Override
    public void output(ByteBuffer buffer) {
      output.add(buffer);
    }

    /**
     * Get the number of bytes in buffers that are allocated to this stream.
     * @return number of bytes in buffers
     */
    public long getBufferSize() {
      long result = 0;
      for(ByteBuffer buf: output) {
        result += buf.capacity();
      }
      return outStream.getBufferSize() + result;
    }

    /**
     * Flush the stream to the codec.
     * @throws IOException
     */
    public void flush() throws IOException {
      outStream.flush();
    }

    /**
     * Clear all of the buffers.
     * @throws IOException
     */
    public void clear() throws IOException {
      outStream.clear();
      output.clear();
    }

    /**
     * Check the state of suppress flag in output stream
     * @return value of suppress flag
     */
    public boolean isSuppressed() {
      return outStream.isSuppressed();
    }

    /**
     * Get the number of bytes that will be written to the output. Assumes
     * the stream has already been flushed.
     * @return the number of bytes
     */
    public long getOutputSize() {
      long result = 0;
      for(ByteBuffer buffer: output) {
        result += buffer.remaining();
      }
      return result;
    }

    /**
     * Write the saved compressed buffers to the OutputStream.
     * @param out the stream to write to
     * @throws IOException
     */
    void spillTo(OutputStream out) throws IOException {
      for(ByteBuffer buffer: output) {
        out.write(buffer.array(), buffer.arrayOffset() + buffer.position(),
          buffer.remaining());
      }
    }

    @Override
    public String toString() {
      return outStream.toString();
    }
  }

  /**
   * An output receiver that writes the ByteBuffers to the output stream
   * as they are received.
   */
  private class DirectStream implements OutStream.OutputReceiver {
    private final FSDataOutputStream output;

    DirectStream(FSDataOutputStream output) {
      this.output = output;
    }

    @Override
    public void output(ByteBuffer buffer) throws IOException {
      output.write(buffer.array(), buffer.arrayOffset() + buffer.position(),
        buffer.remaining());
    }
  }

  private static class RowIndexPositionRecorder implements PositionRecorder {
    private final OrcProto.RowIndexEntry.Builder builder;

    RowIndexPositionRecorder(OrcProto.RowIndexEntry.Builder builder) {
      this.builder = builder;
    }

    @Override
    public void addPosition(long position) {
      builder.addPositions(position);
    }
  }

  /**
   * Interface from the Writer to the TreeWriters. This limits the visibility
   * that the TreeWriters have into the Writer.
   */
  private class StreamFactory {
    /**
     * Create a stream to store part of a column.
     * @param column the column id for the stream
     * @param kind the kind of stream
     * @return The output outStream that the section needs to be written to.
     * @throws IOException
     */
    public OutStream createStream(int column,
                                  OrcProto.Stream.Kind kind
                                  ) throws IOException {
      final StreamName name = new StreamName(column, kind);
      final EnumSet<CompressionCodec.Modifier> modifiers;

      switch (kind) {
        case BLOOM_FILTER:
        case DATA:
        case DICTIONARY_DATA:
          if (getCompressionStrategy() == OrcFile.CompressionStrategy.SPEED) {
            modifiers = EnumSet.of(Modifier.FAST, Modifier.TEXT);
          } else {
            modifiers = EnumSet.of(Modifier.DEFAULT, Modifier.TEXT);
          }
          break;
        case LENGTH:
        case DICTIONARY_COUNT:
        case PRESENT:
        case ROW_INDEX:
        case SECONDARY:
          // easily compressed using the fastest modes
          modifiers = EnumSet.of(Modifier.FASTEST, Modifier.BINARY);
          break;
        default:
          LOG.warn("Missing ORC compression modifiers for " + kind);
          modifiers = null;
          break;
      }

      BufferedStream result = streams.get(name);
      if (result == null) {
        result = new BufferedStream(name.toString(), bufferSize,
            codec == null ? codec : codec.modify(modifiers));
        streams.put(name, result);
      }
      return result.outStream;
    }

    /**
     * Get the next column id.
     * @return a number from 0 to the number of columns - 1
     */
    public int getNextColumnId() {
      return columnCount++;
    }

    /**
     * Get the current column id. After creating all tree writers this count should tell how many
     * columns (including columns within nested complex objects) are created in total.
     * @return current column id
     */
    public int getCurrentColumnId() {
      return columnCount;
    }

    /**
     * Get the stride rate of the row index.
     */
    public int getRowIndexStride() {
      return rowIndexStride;
    }

    /**
     * Should be building the row index.
     * @return true if we are building the index
     */
    public boolean buildIndex() {
      return buildIndex;
    }

    /**
     * Is the ORC file compressed?
     * @return are the streams compressed
     */
    public boolean isCompressed() {
      return codec != null;
    }

    /**
     * Get the encoding strategy to use.
     * @return encoding strategy
     */
    public OrcFile.EncodingStrategy getEncodingStrategy() {
      return encodingStrategy;
    }

    /**
     * Get the compression strategy to use.
     * @return compression strategy
     */
    public OrcFile.CompressionStrategy getCompressionStrategy() {
      return compressionStrategy;
    }

    /**
     * Get the bloom filter columns
     * @return bloom filter columns
     */
    public boolean[] getBloomFilterColumns() {
      return bloomFilterColumns;
    }

    /**
     * Get bloom filter false positive percentage.
     * @return fpp
     */
    public double getBloomFilterFPP() {
      return bloomFilterFpp;
    }

    /**
     * Get the writer's configuration.
     * @return configuration
     */
    public Configuration getConfiguration() {
      return conf;
    }

    /**
     * Get the version of the file to write.
     */
    public OrcFile.Version getVersion() {
      return version;
    }

    public void useWriterTimeZone(boolean val) {
      writeTimeZone = val;
    }

    public boolean hasWriterTimeZone() {
      return writeTimeZone;
    }

    public TimeZone getTimeZone() {
      return timeZone;
    }
  }

  /**
   * The parent class of all of the writers for each column. Each column
   * is written by an instance of this class. The compound types (struct,
   * list, map, and union) have children tree writers that write the children
   * types.
   */
  private abstract static class TreeWriter {
    protected final int id;
    protected final BitFieldWriter isPresent;
    private final boolean isCompressed;
    protected final ColumnStatisticsImpl indexStatistics;
    protected final ColumnStatisticsImpl stripeColStatistics;
    private final ColumnStatisticsImpl fileStatistics;
    protected TreeWriter[] childrenWriters;
    protected final RowIndexPositionRecorder rowIndexPosition;
    private final OrcProto.RowIndex.Builder rowIndex;
    private final OrcProto.RowIndexEntry.Builder rowIndexEntry;
    private final PositionedOutputStream rowIndexStream;
    private final PositionedOutputStream bloomFilterStream;
    protected final BloomFilterIO bloomFilter;
    protected final boolean createBloomFilter;
    private final OrcProto.BloomFilterIndex.Builder bloomFilterIndex;
    private final OrcProto.BloomFilter.Builder bloomFilterEntry;
    private boolean foundNulls;
    private OutStream isPresentOutStream;
    private final List<OrcProto.StripeStatistics.Builder> stripeStatsBuilders;
    private final StreamFactory streamFactory;

    /**
     * Create a tree writer.
     * @param columnId the column id of the column to write
     * @param schema the row schema
     * @param streamFactory limited access to the Writer's data.
     * @param nullable can the value be null?
     * @throws IOException
     */
    TreeWriter(int columnId,
               TypeDescription schema,
               StreamFactory streamFactory,
               boolean nullable) throws IOException {
      this.streamFactory = streamFactory;
      this.isCompressed = streamFactory.isCompressed();
      this.id = columnId;
      if (nullable) {
        isPresentOutStream = streamFactory.createStream(id,
            OrcProto.Stream.Kind.PRESENT);
        isPresent = new BitFieldWriter(isPresentOutStream, 1);
      } else {
        isPresent = null;
      }
      this.foundNulls = false;
      createBloomFilter = streamFactory.getBloomFilterColumns()[columnId];
      indexStatistics = ColumnStatisticsImpl.create(schema);
      stripeColStatistics = ColumnStatisticsImpl.create(schema);
      fileStatistics = ColumnStatisticsImpl.create(schema);
      childrenWriters = new TreeWriter[0];
      rowIndex = OrcProto.RowIndex.newBuilder();
      rowIndexEntry = OrcProto.RowIndexEntry.newBuilder();
      rowIndexPosition = new RowIndexPositionRecorder(rowIndexEntry);
      stripeStatsBuilders = Lists.newArrayList();
      if (streamFactory.buildIndex()) {
        rowIndexStream = streamFactory.createStream(id, OrcProto.Stream.Kind.ROW_INDEX);
      } else {
        rowIndexStream = null;
      }
      if (createBloomFilter) {
        bloomFilterEntry = OrcProto.BloomFilter.newBuilder();
        bloomFilterIndex = OrcProto.BloomFilterIndex.newBuilder();
        bloomFilterStream = streamFactory.createStream(id, OrcProto.Stream.Kind.BLOOM_FILTER);
        bloomFilter = new BloomFilterIO(streamFactory.getRowIndexStride(),
            streamFactory.getBloomFilterFPP());
      } else {
        bloomFilterEntry = null;
        bloomFilterIndex = null;
        bloomFilterStream = null;
        bloomFilter = null;
      }
    }

    protected OrcProto.RowIndex.Builder getRowIndex() {
      return rowIndex;
    }

    protected ColumnStatisticsImpl getStripeStatistics() {
      return stripeColStatistics;
    }

    protected ColumnStatisticsImpl getFileStatistics() {
      return fileStatistics;
    }

    protected OrcProto.RowIndexEntry.Builder getRowIndexEntry() {
      return rowIndexEntry;
    }

    IntegerWriter createIntegerWriter(PositionedOutputStream output,
                                      boolean signed, boolean isDirectV2,
                                      StreamFactory writer) {
      if (isDirectV2) {
        boolean alignedBitpacking = false;
        if (writer.getEncodingStrategy().equals(OrcFile.EncodingStrategy.SPEED)) {
          alignedBitpacking = true;
        }
        return new RunLengthIntegerWriterV2(output, signed, alignedBitpacking);
      } else {
        return new RunLengthIntegerWriter(output, signed);
      }
    }

    boolean isNewWriteFormat(StreamFactory writer) {
      return writer.getVersion() != OrcFile.Version.V_0_11;
    }

    /**
     * Add a new value to the column.
     * @param datum
     * @throws IOException
     */
    void write(Datum datum) throws IOException {
      if (datum != null && datum.isNotNull()) {
        indexStatistics.increment();
      } else {
        indexStatistics.setNull();
      }
      if (isPresent != null) {
        if(datum == null || datum.isNull()) {
          foundNulls = true;
          isPresent.write(0);
        }
        else {
          isPresent.write(1);
        }
      }
    }

    void write(Tuple tuple) throws IOException {
      if (tuple != null) {
        indexStatistics.increment();
      } else {
        indexStatistics.setNull();
      }
      if (isPresent != null) {
        if (tuple == null) {
          foundNulls = true;
          isPresent.write(0);
        } else {
          isPresent.write(1);
        }
      }
    }

    private void removeIsPresentPositions() {
      for(int i=0; i < rowIndex.getEntryCount(); ++i) {
        RowIndexEntry.Builder entry = rowIndex.getEntryBuilder(i);
        List<Long> positions = entry.getPositionsList();
        // bit streams use 3 positions if uncompressed, 4 if compressed
        positions = positions.subList(isCompressed ? 4 : 3, positions.size());
        entry.clearPositions();
        entry.addAllPositions(positions);
      }
    }

    /**
     * Write the stripe out to the file.
     * @param builder the stripe footer that contains the information about the
     *                layout of the stripe. The TreeWriter is required to update
     *                the footer with its information.
     * @param requiredIndexEntries the number of index entries that are
     *                             required. this is to check to make sure the
     *                             row index is well formed.
     * @throws IOException
     */
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      if (isPresent != null) {
        isPresent.flush();

        // if no nulls are found in a stream, then suppress the stream
        if(!foundNulls) {
          isPresentOutStream.suppress();
          // since isPresent bitstream is suppressed, update the index to
          // remove the positions of the isPresent stream
          if (rowIndexStream != null) {
            removeIsPresentPositions();
          }
        }
      }

      // merge stripe-level column statistics to file statistics and write it to
      // stripe statistics
      OrcProto.StripeStatistics.Builder stripeStatsBuilder = OrcProto.StripeStatistics.newBuilder();
      writeStripeStatistics(stripeStatsBuilder, this);
      stripeStatsBuilders.add(stripeStatsBuilder);

      // reset the flag for next stripe
      foundNulls = false;

      builder.addColumns(getEncoding());
      if (streamFactory.hasWriterTimeZone()) {
        builder.setWriterTimezone(streamFactory.getTimeZone().getID());
      }
      if (rowIndexStream != null) {
        if (rowIndex.getEntryCount() != requiredIndexEntries) {
          throw new IllegalArgumentException("Column has wrong number of " +
               "index entries found: " + rowIndex.getEntryCount() + " expected: " +
               requiredIndexEntries);
        }
        rowIndex.build().writeTo(rowIndexStream);
        rowIndexStream.flush();
      }
      rowIndex.clear();
      rowIndexEntry.clear();

      // write the bloom filter to out stream
      if (bloomFilterStream != null) {
        bloomFilterIndex.build().writeTo(bloomFilterStream);
        bloomFilterStream.flush();
        bloomFilterIndex.clear();
        bloomFilterEntry.clear();
      }
    }

    private void writeStripeStatistics(OrcProto.StripeStatistics.Builder builder,
        TreeWriter treeWriter) {
      treeWriter.fileStatistics.merge(treeWriter.stripeColStatistics);
      builder.addColStats(treeWriter.stripeColStatistics.serialize().build());
      treeWriter.stripeColStatistics.reset();
      for (TreeWriter child : treeWriter.getChildrenWriters()) {
        writeStripeStatistics(builder, child);
      }
    }

    TreeWriter[] getChildrenWriters() {
      return childrenWriters;
    }

    /**
     * Get the encoding for this column.
     * @return the information about the encoding of this column
     */
    OrcProto.ColumnEncoding getEncoding() {
      return OrcProto.ColumnEncoding.newBuilder().setKind(
          OrcProto.ColumnEncoding.Kind.DIRECT).build();
    }

    /**
     * Create a row index entry with the previous location and the current
     * index statistics. Also merges the index statistics into the file
     * statistics before they are cleared. Finally, it records the start of the
     * next index and ensures all of the children columns also create an entry.
     * @throws IOException
     */
    void createRowIndexEntry() throws IOException {
      stripeColStatistics.merge(indexStatistics);
      rowIndexEntry.setStatistics(indexStatistics.serialize());
      indexStatistics.reset();
      rowIndex.addEntry(rowIndexEntry);
      rowIndexEntry.clear();
      addBloomFilterEntry();
      recordPosition(rowIndexPosition);
      for(TreeWriter child: childrenWriters) {
        child.createRowIndexEntry();
      }
    }

    void addBloomFilterEntry() {
      if (createBloomFilter) {
        bloomFilterEntry.setNumHashFunctions(bloomFilter.getNumHashFunctions());
        bloomFilterEntry.addAllBitset(Longs.asList(bloomFilter.getBitSet()));
        bloomFilterIndex.addBloomFilter(bloomFilterEntry.build());
        bloomFilter.reset();
        bloomFilterEntry.clear();
      }
    }

    /**
     * Record the current position in each of this column's streams.
     * @param recorder where should the locations be recorded
     * @throws IOException
     */
    void recordPosition(PositionRecorder recorder) throws IOException {
      if (isPresent != null) {
        isPresent.getPosition(recorder);
      }
    }

    /**
     * Estimate how much memory the writer is consuming excluding the streams.
     * @return the number of bytes.
     */
    long estimateMemory() {
      long result = 0;
      for (TreeWriter child: childrenWriters) {
        result += child.estimateMemory();
      }
      return result;
    }
  }

  private static class BooleanTreeWriter extends TreeWriter {
    private final BitFieldWriter writer;

    BooleanTreeWriter(int columnId,
                      TypeDescription schema,
                      StreamFactory writer,
                      boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      PositionedOutputStream out = writer.createStream(id,
          OrcProto.Stream.Kind.DATA);
      this.writer = new BitFieldWriter(out, 1);
      recordPosition(rowIndexPosition);
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        boolean val = datum.asBool();
        indexStatistics.updateBoolean(val, 1);
        writer.write(val ? 1 : 0);
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      writer.flush();
      recordPosition(rowIndexPosition);
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      writer.getPosition(recorder);
    }
  }

  private static class ByteTreeWriter extends TreeWriter {
    private final RunLengthByteWriter writer;

    ByteTreeWriter(int columnId,
                   TypeDescription schema,
                   StreamFactory writer,
                   boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      this.writer = new RunLengthByteWriter(writer.createStream(id,
          OrcProto.Stream.Kind.DATA));
      recordPosition(rowIndexPosition);
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        byte val = datum.asByte();
        indexStatistics.updateInteger(val, 1);
        if (createBloomFilter) {
          bloomFilter.addLong(val);
        }
        writer.write(val);
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      writer.flush();
      recordPosition(rowIndexPosition);
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      writer.getPosition(recorder);
    }
  }

  private static class IntegerTreeWriter extends TreeWriter {
    private final IntegerWriter writer;
    private boolean isDirectV2 = true;

    IntegerTreeWriter(int columnId,
                      TypeDescription schema,
                      StreamFactory writer,
                      boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      OutStream out = writer.createStream(id,
          OrcProto.Stream.Kind.DATA);
      this.isDirectV2 = isNewWriteFormat(writer);
      this.writer = createIntegerWriter(out, true, isDirectV2, writer);
      recordPosition(rowIndexPosition);
    }

    @Override
    OrcProto.ColumnEncoding getEncoding() {
      if (isDirectV2) {
        return OrcProto.ColumnEncoding.newBuilder()
            .setKind(OrcProto.ColumnEncoding.Kind.DIRECT_V2).build();
      }
      return OrcProto.ColumnEncoding.newBuilder()
          .setKind(OrcProto.ColumnEncoding.Kind.DIRECT).build();
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        long val;
        if (datum instanceof Int4Datum || datum instanceof Inet4Datum) {
          val = datum.asInt4();
        } else if (datum instanceof Int8Datum) {
          val = datum.asInt8();
        } else {
          val = datum.asInt2();
        }
        indexStatistics.updateInteger(val, 1);
        if (createBloomFilter) {
          // integers are converted to longs in column statistics and during SARG evaluation
          bloomFilter.addLong(val);
        }
        writer.write(val);
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      writer.flush();
      recordPosition(rowIndexPosition);
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      writer.getPosition(recorder);
    }
  }

  private static class FloatTreeWriter extends TreeWriter {
    private final PositionedOutputStream stream;
    private final SerializationUtils utils;

    FloatTreeWriter(int columnId,
                    TypeDescription schema,
                    StreamFactory writer,
                    boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      this.stream = writer.createStream(id,
          OrcProto.Stream.Kind.DATA);
      this.utils = new SerializationUtils();
      recordPosition(rowIndexPosition);
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        float val = datum.asFloat4();
        indexStatistics.updateDouble(val);
        if (createBloomFilter) {
          // floats are converted to doubles in column statistics and during SARG evaluation
          bloomFilter.addDouble(val);
        }
        utils.writeFloat(stream, val);
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      stream.flush();
      recordPosition(rowIndexPosition);
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      stream.getPosition(recorder);
    }
  }

  private static class DoubleTreeWriter extends TreeWriter {
    private final PositionedOutputStream stream;
    private final SerializationUtils utils;

    DoubleTreeWriter(int columnId,
                     TypeDescription schema,
                     StreamFactory writer,
                     boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      this.stream = writer.createStream(id,
          OrcProto.Stream.Kind.DATA);
      this.utils = new SerializationUtils();
      recordPosition(rowIndexPosition);
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        double val = datum.asFloat8();
        indexStatistics.updateDouble(val);
        if (createBloomFilter) {
          bloomFilter.addDouble(val);
        }
        utils.writeDouble(stream, val);
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      stream.flush();
      recordPosition(rowIndexPosition);
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      stream.getPosition(recorder);
    }
  }

  private static abstract class StringBaseTreeWriter extends TreeWriter {
    private static final int INITIAL_DICTIONARY_SIZE = 4096;
    private final OutStream stringOutput;
    private final IntegerWriter lengthOutput;
    private final IntegerWriter rowOutput;
    protected final StringRedBlackTree dictionary =
        new StringRedBlackTree(INITIAL_DICTIONARY_SIZE);
    protected final DynamicIntArray rows = new DynamicIntArray();
    protected final PositionedOutputStream directStreamOutput;
    protected final IntegerWriter directLengthOutput;
    private final List<OrcProto.RowIndexEntry> savedRowIndex =
        new ArrayList<OrcProto.RowIndexEntry>();
    private final boolean buildIndex;
    private final List<Long> rowIndexValueCount = new ArrayList<Long>();
    // If the number of keys in a dictionary is greater than this fraction of
    //the total number of non-null rows, turn off dictionary encoding
    private final double dictionaryKeySizeThreshold;
    protected boolean useDictionaryEncoding = true;
    private boolean isDirectV2 = true;
    private boolean doneDictionaryCheck;
    protected final boolean strideDictionaryCheck;

    StringBaseTreeWriter(int columnId,
                         TypeDescription schema,
                         StreamFactory writer,
                         boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      this.isDirectV2 = isNewWriteFormat(writer);
      stringOutput = writer.createStream(id,
          OrcProto.Stream.Kind.DICTIONARY_DATA);
      lengthOutput = createIntegerWriter(writer.createStream(id,
          OrcProto.Stream.Kind.LENGTH), false, isDirectV2, writer);
      rowOutput = createIntegerWriter(writer.createStream(id,
          OrcProto.Stream.Kind.DATA), false, isDirectV2, writer);
      recordPosition(rowIndexPosition);
      rowIndexValueCount.add(0L);
      buildIndex = writer.buildIndex();
      directStreamOutput = writer.createStream(id, OrcProto.Stream.Kind.DATA);
      directLengthOutput = createIntegerWriter(writer.createStream(id,
          OrcProto.Stream.Kind.LENGTH), false, isDirectV2, writer);
      Configuration conf = writer.getConfiguration();
      dictionaryKeySizeThreshold =
          org.apache.orc.OrcConf.DICTIONARY_KEY_SIZE_THRESHOLD.getDouble(conf);
      strideDictionaryCheck =
          org.apache.orc.OrcConf.ROW_INDEX_STRIDE_DICTIONARY_CHECK.getBoolean(conf);
      doneDictionaryCheck = false;
    }

    private boolean checkDictionaryEncoding() {
      if (!doneDictionaryCheck) {
        // Set the flag indicating whether or not to use dictionary encoding
        // based on whether or not the fraction of distinct keys over number of
        // non-null rows is less than the configured threshold
        float ratio = rows.size() > 0 ? (float) (dictionary.size()) / rows.size() : 0.0f;
        useDictionaryEncoding = !isDirectV2 || ratio <= dictionaryKeySizeThreshold;
        doneDictionaryCheck = true;
      }
      return useDictionaryEncoding;
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      // if rows in stripe is less than dictionaryCheckAfterRows, dictionary
      // checking would not have happened. So do it again here.
      checkDictionaryEncoding();

      if (useDictionaryEncoding) {
        flushDictionary();
      } else {
        // flushout any left over entries from dictionary
        if (rows.size() > 0) {
          flushDictionary();
        }

        // suppress the stream for every stripe if dictionary is disabled
        stringOutput.suppress();
      }

      // we need to build the rowindex before calling super, since it
      // writes it out.
      super.writeStripe(builder, requiredIndexEntries);
      stringOutput.flush();
      lengthOutput.flush();
      rowOutput.flush();
      directStreamOutput.flush();
      directLengthOutput.flush();
      // reset all of the fields to be ready for the next stripe.
      dictionary.clear();
      savedRowIndex.clear();
      rowIndexValueCount.clear();
      recordPosition(rowIndexPosition);
      rowIndexValueCount.add(0L);

      if (!useDictionaryEncoding) {
        // record the start positions of first index stride of next stripe i.e
        // beginning of the direct streams when dictionary is disabled
        recordDirectStreamPosition();
      }
    }

    private void flushDictionary() throws IOException {
      final int[] dumpOrder = new int[dictionary.size()];

      if (useDictionaryEncoding) {
        // Write the dictionary by traversing the red-black tree writing out
        // the bytes and lengths; and creating the map from the original order
        // to the final sorted order.

        dictionary.visit(new StringRedBlackTree.Visitor() {
          private int currentId = 0;
          @Override
          public void visit(StringRedBlackTree.VisitorContext context
          ) throws IOException {
            context.writeBytes(stringOutput);
            lengthOutput.write(context.getLength());
            dumpOrder[context.getOriginalPosition()] = currentId++;
          }
        });
      } else {
        // for direct encoding, we don't want the dictionary data stream
        stringOutput.suppress();
      }
      int length = rows.size();
      int rowIndexEntry = 0;
      OrcProto.RowIndex.Builder rowIndex = getRowIndex();
      Text text = new Text();
      // write the values translated into the dump order.
      for(int i = 0; i <= length; ++i) {
        // now that we are writing out the row values, we can finalize the
        // row index
        if (buildIndex) {
          while (i == rowIndexValueCount.get(rowIndexEntry) &&
              rowIndexEntry < savedRowIndex.size()) {
            OrcProto.RowIndexEntry.Builder base =
                savedRowIndex.get(rowIndexEntry++).toBuilder();
            if (useDictionaryEncoding) {
              rowOutput.getPosition(new RowIndexPositionRecorder(base));
            } else {
              PositionRecorder posn = new RowIndexPositionRecorder(base);
              directStreamOutput.getPosition(posn);
              directLengthOutput.getPosition(posn);
            }
            rowIndex.addEntry(base.build());
          }
        }
        if (i != length) {
          if (useDictionaryEncoding) {
            rowOutput.write(dumpOrder[rows.get(i)]);
          } else {
            dictionary.getText(text, rows.get(i));
            directStreamOutput.write(text.getBytes(), 0, text.getLength());
            directLengthOutput.write(text.getLength());
          }
        }
      }
      rows.clear();
    }

    @Override
    OrcProto.ColumnEncoding getEncoding() {
      // Returns the encoding used for the last call to writeStripe
      if (useDictionaryEncoding) {
        if(isDirectV2) {
          return OrcProto.ColumnEncoding.newBuilder().setKind(
              OrcProto.ColumnEncoding.Kind.DICTIONARY_V2).
              setDictionarySize(dictionary.size()).build();
        }
        return OrcProto.ColumnEncoding.newBuilder().setKind(
            OrcProto.ColumnEncoding.Kind.DICTIONARY).
            setDictionarySize(dictionary.size()).build();
      } else {
        if(isDirectV2) {
          return OrcProto.ColumnEncoding.newBuilder().setKind(
              OrcProto.ColumnEncoding.Kind.DIRECT_V2).build();
        }
        return OrcProto.ColumnEncoding.newBuilder().setKind(
            OrcProto.ColumnEncoding.Kind.DIRECT).build();
      }
    }

    /**
     * This method doesn't call the super method, because unlike most of the
     * other TreeWriters, this one can't record the position in the streams
     * until the stripe is being flushed. Therefore it saves all of the entries
     * and augments them with the final information as the stripe is written.
     * @throws IOException
     */
    @Override
    void createRowIndexEntry() throws IOException {
      getStripeStatistics().merge(indexStatistics);
      OrcProto.RowIndexEntry.Builder rowIndexEntry = getRowIndexEntry();
      rowIndexEntry.setStatistics(indexStatistics.serialize());
      indexStatistics.reset();
      OrcProto.RowIndexEntry base = rowIndexEntry.build();
      savedRowIndex.add(base);
      rowIndexEntry.clear();
      addBloomFilterEntry();
      recordPosition(rowIndexPosition);
      rowIndexValueCount.add(Long.valueOf(rows.size()));
      if (strideDictionaryCheck) {
        checkDictionaryEncoding();
      }
      if (!useDictionaryEncoding) {
        if (rows.size() > 0) {
          flushDictionary();
          // just record the start positions of next index stride
          recordDirectStreamPosition();
        } else {
          // record the start positions of next index stride
          recordDirectStreamPosition();
          getRowIndex().addEntry(base);
        }
      }
    }

    private void recordDirectStreamPosition() throws IOException {
      directStreamOutput.getPosition(rowIndexPosition);
      directLengthOutput.getPosition(rowIndexPosition);
    }

    @Override
    long estimateMemory() {
      return rows.getSizeInBytes() + dictionary.getSizeInBytes();
    }
  }

  private static class StringTreeWriter extends StringBaseTreeWriter {
    StringTreeWriter(int columnId,
                     TypeDescription schema,
                     StreamFactory writer,
                     boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        if (useDictionaryEncoding || !strideDictionaryCheck) {
          rows.add(dictionary.add(datum.toString()));
        } else {
          // write data and length
          directStreamOutput.write(datum.asByteArray(), 0, datum.size());
          directLengthOutput.write(datum.size());
        }
        byte[] buf = datum.asByteArray();
        indexStatistics.updateString(buf, 0, buf.length, 1);
        if (createBloomFilter) {
          bloomFilter.addBytes(buf, 0, buf.length);
        }
      }
    }
  }

  /**
   * Under the covers, char is written to ORC the same way as string.
   */
  private static class CharTreeWriter extends StringTreeWriter {
    private final int itemLength;
    private final byte[] padding;

    CharTreeWriter(int columnId,
                   TypeDescription schema,
                   StreamFactory writer,
                   boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      itemLength = schema.getMaxLength();
      padding = new byte[itemLength];
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        byte[] ptr;
        byte[] buf = datum.asByteArray();
        if (buf.length >= itemLength) {
          ptr = buf;
        } else {
          ptr = padding;
          System.arraycopy(buf, 0, ptr, 0, buf.length);
          Arrays.fill(ptr, buf.length, itemLength, (byte) ' ');
        }
        if (useDictionaryEncoding || !strideDictionaryCheck) {
          rows.add(dictionary.add(ptr, 0, itemLength));
        } else {
          // write data and length
          directStreamOutput.write(ptr, 0, itemLength);
          directLengthOutput.write(itemLength);
        }

        indexStatistics.updateString(ptr, 0, ptr.length, 1);
        if (createBloomFilter) {
          bloomFilter.addBytes(ptr, 0, ptr.length);
        }
      }
    }
  }

  private static class BinaryTreeWriter extends TreeWriter {
    private final PositionedOutputStream stream;
    private final IntegerWriter length;
    private boolean isDirectV2 = true;

    BinaryTreeWriter(int columnId,
                     TypeDescription schema,
                     StreamFactory writer,
                     boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      this.stream = writer.createStream(id,
          OrcProto.Stream.Kind.DATA);
      this.isDirectV2 = isNewWriteFormat(writer);
      this.length = createIntegerWriter(writer.createStream(id,
          OrcProto.Stream.Kind.LENGTH), false, isDirectV2, writer);
      recordPosition(rowIndexPosition);
    }

    @Override
    OrcProto.ColumnEncoding getEncoding() {
      if (isDirectV2) {
        return OrcProto.ColumnEncoding.newBuilder()
            .setKind(OrcProto.ColumnEncoding.Kind.DIRECT_V2).build();
      }
      return OrcProto.ColumnEncoding.newBuilder()
          .setKind(OrcProto.ColumnEncoding.Kind.DIRECT).build();
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        byte[] buf = datum.asByteArray();
        stream.write(buf, 0, buf.length);
        length.write(datum.size());
        indexStatistics.updateBinary(buf, 0, buf.length, 1);
        if (createBloomFilter) {
          bloomFilter.addBytes(buf, 0, buf.length);
        }
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      stream.flush();
      length.flush();
      recordPosition(rowIndexPosition);
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      stream.getPosition(recorder);
      length.getPosition(recorder);
    }
  }

  static final String BASE_TIMESTAMP_STRING = "2015-01-01 00:00:00";

  private static class TimestampTreeWriter extends TreeWriter {
    private final IntegerWriter seconds;
    private final IntegerWriter nanos;
    private final boolean isDirectV2;
    private final long base_timestamp;
    private TimeZone timeZone;

    TimestampTreeWriter(int columnId,
                        TypeDescription schema,
                        StreamFactory writer,
                        boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      this.isDirectV2 = isNewWriteFormat(writer);
      this.seconds = createIntegerWriter(writer.createStream(id,
          OrcProto.Stream.Kind.DATA), true, isDirectV2, writer);
      this.nanos = createIntegerWriter(writer.createStream(id,
          OrcProto.Stream.Kind.SECONDARY), false, isDirectV2, writer);
      recordPosition(rowIndexPosition);
      // for unit tests to set different time zones
      this.base_timestamp = Timestamp.valueOf(BASE_TIMESTAMP_STRING).getTime() / DateTimeConstants.MSECS_PER_SEC;
      writer.useWriterTimeZone(true);
      timeZone = writer.getTimeZone();
    }

    @Override
    OrcProto.ColumnEncoding getEncoding() {
      if (isDirectV2) {
        return OrcProto.ColumnEncoding.newBuilder()
            .setKind(OrcProto.ColumnEncoding.Kind.DIRECT_V2).build();
      }
      return OrcProto.ColumnEncoding.newBuilder()
          .setKind(OrcProto.ColumnEncoding.Kind.DIRECT).build();
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        long javaTimestamp = DateTimeUtil.julianTimeToJavaTime(datum.asInt8());

        // revise timestamp value depends on timezone
        javaTimestamp += timeZone.getRawOffset();

        Timestamp val = new Timestamp(javaTimestamp);
        indexStatistics.updateTimestamp(val);
        seconds.write((val.getTime() / DateTimeConstants.MSECS_PER_SEC) - base_timestamp);
        nanos.write(formatNanos(val.getNanos()));
        if (createBloomFilter) {
          bloomFilter.addLong(val.getTime());
        }
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      seconds.flush();
      nanos.flush();
      recordPosition(rowIndexPosition);
    }

    private static long formatNanos(int nanos) {
      if (nanos == 0) {
        return 0;
      } else if (nanos % 100 != 0) {
        return ((long) nanos) << 3;
      } else {
        nanos /= 100;
        int trailingZeros = 1;
        while (nanos % 10 == 0 && trailingZeros < 7) {
          nanos /= 10;
          trailingZeros += 1;
        }
        return ((long) nanos) << 3 | trailingZeros;
      }
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      seconds.getPosition(recorder);
      nanos.getPosition(recorder);
    }
  }

  private static class DateTreeWriter extends TreeWriter {
    private final IntegerWriter writer;
    private final boolean isDirectV2;

    DateTreeWriter(int columnId,
                   TypeDescription schema,
                   StreamFactory writer,
                   boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      OutStream out = writer.createStream(id,
          OrcProto.Stream.Kind.DATA);
      this.isDirectV2 = isNewWriteFormat(writer);
      this.writer = createIntegerWriter(out, true, isDirectV2, writer);
      recordPosition(rowIndexPosition);
    }

    @Override
    void write(Datum datum) throws IOException {
      super.write(datum);
      if (datum != null && datum.isNotNull()) {
        int daysSinceEpoch = datum.asInt4() - DateTimeUtil.DAYS_FROM_JULIAN_TO_EPOCH;
        // Using the Writable here as it's used directly for writing as well as for stats.
        indexStatistics.updateDate(daysSinceEpoch);
        writer.write(daysSinceEpoch);
        if (createBloomFilter) {
          bloomFilter.addLong(daysSinceEpoch);
        }
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      writer.flush();
      recordPosition(rowIndexPosition);
    }

    @Override
    void recordPosition(PositionRecorder recorder) throws IOException {
      super.recordPosition(recorder);
      writer.getPosition(recorder);
    }

    @Override
    OrcProto.ColumnEncoding getEncoding() {
      if (isDirectV2) {
        return OrcProto.ColumnEncoding.newBuilder()
          .setKind(OrcProto.ColumnEncoding.Kind.DIRECT_V2).build();
      }
      return OrcProto.ColumnEncoding.newBuilder()
        .setKind(OrcProto.ColumnEncoding.Kind.DIRECT).build();
    }
  }

  private static class StructTreeWriter extends TreeWriter {
    StructTreeWriter(int columnId,
                     TypeDescription schema,
                     StreamFactory writer,
                     boolean nullable) throws IOException {
      super(columnId, schema, writer, nullable);
      List<TypeDescription> children = schema.getChildren();
      childrenWriters = new TreeWriter[children.size()];
      for(int i=0; i < childrenWriters.length; ++i) {
        childrenWriters[i] = createTreeWriter(
            children.get(i), writer,
            true);
      }
      recordPosition(rowIndexPosition);
    }

    @Override
    void write(Datum datum) throws IOException {
    }

    void writeTuple(Tuple tuple) throws IOException {
      super.write(tuple);
      if (tuple != null) {
        for(int i = 0; i < childrenWriters.length; ++i) {
          childrenWriters[i].write(tuple.asDatum(i));
        }
      }
    }

    @Override
    void writeStripe(OrcProto.StripeFooter.Builder builder,
                     int requiredIndexEntries) throws IOException {
      super.writeStripe(builder, requiredIndexEntries);
      for(TreeWriter child: childrenWriters) {
        child.writeStripe(builder, requiredIndexEntries);
      }
      recordPosition(rowIndexPosition);
    }
  }

  private static TreeWriter createTreeWriter(TypeDescription schema,
                                             StreamFactory streamFactory,
                                             boolean nullable) throws IOException {
    switch (schema.getCategory()) {
      case BOOLEAN:
        return new BooleanTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case BYTE:
        return new ByteTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case SHORT:
      case INT:
      case LONG:
        return new IntegerTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case FLOAT:
        return new FloatTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case DOUBLE:
        return new DoubleTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case STRING:
        return new StringTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case CHAR:
        return new CharTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case BINARY:
        return new BinaryTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case TIMESTAMP:
        return new TimestampTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case DATE:
        return new DateTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      case STRUCT:
        return new StructTreeWriter(streamFactory.getNextColumnId(),
            schema, streamFactory, nullable);
      default:
        throw new IllegalArgumentException("Bad category: " +
            schema.getCategory());
    }
  }

  private static void writeTypes(OrcProto.Footer.Builder builder,
                                 TypeDescription schema) {
    OrcProto.Type.Builder type = OrcProto.Type.newBuilder();
    List<TypeDescription> children = schema.getChildren();
    switch (schema.getCategory()) {
      case BOOLEAN:
        type.setKind(OrcProto.Type.Kind.BOOLEAN);
        break;
      case BYTE:
        type.setKind(OrcProto.Type.Kind.BYTE);
        break;
      case SHORT:
        type.setKind(OrcProto.Type.Kind.SHORT);
        break;
      case INT:
        type.setKind(OrcProto.Type.Kind.INT);
        break;
      case LONG:
        type.setKind(OrcProto.Type.Kind.LONG);
        break;
      case FLOAT:
        type.setKind(OrcProto.Type.Kind.FLOAT);
        break;
      case DOUBLE:
        type.setKind(OrcProto.Type.Kind.DOUBLE);
        break;
      case STRING:
        type.setKind(OrcProto.Type.Kind.STRING);
        break;
      case CHAR:
        type.setKind(OrcProto.Type.Kind.CHAR);
        type.setMaximumLength(schema.getMaxLength());
        break;
      case VARCHAR:
        type.setKind(OrcProto.Type.Kind.VARCHAR);
        type.setMaximumLength(schema.getMaxLength());
        break;
      case BINARY:
        type.setKind(OrcProto.Type.Kind.BINARY);
        break;
      case TIMESTAMP:
        type.setKind(OrcProto.Type.Kind.TIMESTAMP);
        break;
      case DATE:
        type.setKind(OrcProto.Type.Kind.DATE);
        break;
      case DECIMAL:
        type.setKind(OrcProto.Type.Kind.DECIMAL);
        type.setPrecision(schema.getPrecision());
        type.setScale(schema.getScale());
        break;
      case LIST:
        type.setKind(OrcProto.Type.Kind.LIST);
        type.addSubtypes(children.get(0).getId());
        break;
      case MAP:
        type.setKind(OrcProto.Type.Kind.MAP);
        for(TypeDescription t: children) {
          type.addSubtypes(t.getId());
        }
        break;
      case STRUCT:
        type.setKind(OrcProto.Type.Kind.STRUCT);
        for(TypeDescription t: children) {
          type.addSubtypes(t.getId());
        }
        for(String field: schema.getFieldNames()) {
          type.addFieldNames(field);
        }
        break;
      case UNION:
        type.setKind(OrcProto.Type.Kind.UNION);
        for(TypeDescription t: children) {
          type.addSubtypes(t.getId());
        }
        break;
      default:
        throw new IllegalArgumentException("Unknown category: " +
            schema.getCategory());
    }
    builder.addTypes(type);
    if (children != null) {
      for(TypeDescription child: children) {
        writeTypes(builder, child);
      }
    }
  }

  @VisibleForTesting
  FSDataOutputStream getStream() throws IOException {
    if (rawWriter == null) {
      rawWriter = fs.create(path, false, HDFS_BUFFER_SIZE,
                            fs.getDefaultReplication(path), blockSize);
      rawWriter.writeBytes(OrcFile.MAGIC);
      headerLength = rawWriter.getPos();
      writer = new OutStream("metadata", bufferSize, codec,
                             new DirectStream(rawWriter));
      protobufWriter = CodedOutputStream.newInstance(writer);
    }
    return rawWriter;
  }

  private void createRowIndexEntry() throws IOException {
    treeWriter.createRowIndexEntry();
    rowsInIndex = 0;
  }

  private void flushStripe() throws IOException {
    getStream();
    if (buildIndex && rowsInIndex != 0) {
      createRowIndexEntry();
    }
    if (rowsInStripe != 0) {
      if (callback != null) {
        callback.preStripeWrite(callbackContext);
      }
      // finalize the data for the stripe
      int requiredIndexEntries = rowIndexStride == 0 ? 0 :
          (int) ((rowsInStripe + rowIndexStride - 1) / rowIndexStride);
      OrcProto.StripeFooter.Builder builder =
          OrcProto.StripeFooter.newBuilder();
      treeWriter.writeStripe(builder, requiredIndexEntries);
      long indexSize = 0;
      long dataSize = 0;
      for(Map.Entry<StreamName, BufferedStream> pair: streams.entrySet()) {
        BufferedStream stream = pair.getValue();
        if (!stream.isSuppressed()) {
          stream.flush();
          StreamName name = pair.getKey();
          long streamSize = pair.getValue().getOutputSize();
          builder.addStreams(OrcProto.Stream.newBuilder()
              .setColumn(name.getColumn())
              .setKind(name.getKind())
              .setLength(streamSize));
          if (StreamName.Area.INDEX == name.getArea()) {
            indexSize += streamSize;
          } else {
            dataSize += streamSize;
          }
        }
      }
      OrcProto.StripeFooter footer = builder.build();

      // Do we need to pad the file so the stripe doesn't straddle a block
      // boundary?
      long start = rawWriter.getPos();
      final long currentStripeSize = indexSize + dataSize + footer.getSerializedSize();
      final long available = blockSize - (start % blockSize);
      final long overflow = currentStripeSize - adjustedStripeSize;
      final float availRatio = (float) available / (float) defaultStripeSize;

      if (availRatio > 0.0f && availRatio < 1.0f
          && availRatio > paddingTolerance) {
        // adjust default stripe size to fit into remaining space, also adjust
        // the next stripe for correction based on the current stripe size
        // and user specified padding tolerance. Since stripe size can overflow
        // the default stripe size we should apply this correction to avoid
        // writing portion of last stripe to next hdfs block.
        double correction = overflow > 0 ? (double) overflow
            / (double) adjustedStripeSize : 0.0;

        // correction should not be greater than user specified padding
        // tolerance
        correction = correction > paddingTolerance ? paddingTolerance
            : correction;

        // adjust next stripe size based on current stripe estimate correction
        adjustedStripeSize = (long) ((1.0f - correction) * (availRatio * defaultStripeSize));
      } else if (availRatio >= 1.0) {
        adjustedStripeSize = defaultStripeSize;
      }

      if (availRatio < paddingTolerance && addBlockPadding) {
        long padding = blockSize - (start % blockSize);
        byte[] pad = new byte[(int) Math.min(HDFS_BUFFER_SIZE, padding)];
        LOG.info(String.format("Padding ORC by %d bytes (<=  %.2f * %d)",
            padding, availRatio, defaultStripeSize));
        start += padding;
        while (padding > 0) {
          int writeLen = (int) Math.min(padding, pad.length);
          rawWriter.write(pad, 0, writeLen);
          padding -= writeLen;
        }
        adjustedStripeSize = defaultStripeSize;
      } else if (currentStripeSize < blockSize
          && (start % blockSize) + currentStripeSize > blockSize) {
        // even if you don't pad, reset the default stripe size when crossing a
        // block boundary
        adjustedStripeSize = defaultStripeSize;
      }

      // write out the data streams
      for(Map.Entry<StreamName, BufferedStream> pair: streams.entrySet()) {
        BufferedStream stream = pair.getValue();
        if (!stream.isSuppressed()) {
          stream.spillTo(rawWriter);
        }
        stream.clear();
      }
      footer.writeTo(protobufWriter);
      protobufWriter.flush();
      writer.flush();
      long footerLength = rawWriter.getPos() - start - dataSize - indexSize;
      OrcProto.StripeInformation dirEntry =
          OrcProto.StripeInformation.newBuilder()
              .setOffset(start)
              .setNumberOfRows(rowsInStripe)
              .setIndexLength(indexSize)
              .setDataLength(dataSize)
              .setFooterLength(footerLength).build();
      stripes.add(dirEntry);
      rowCount += rowsInStripe;
      rowsInStripe = 0;
    }
  }

  private long computeRawDataSize() {
    return getRawDataSize(treeWriter, schema);
  }

  private long getRawDataSize(TreeWriter child,
                              TypeDescription schema) {
    long total = 0;
    long numVals = child.fileStatistics.getNumberOfValues();
    switch (schema.getCategory()) {
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case FLOAT:
        return numVals * JavaDataModel.get().primitive1();
      case LONG:
      case DOUBLE:
        return numVals * JavaDataModel.get().primitive2();
      case STRING:
      case VARCHAR:
      case CHAR:
        // ORC strings are converted to java Strings. so use JavaDataModel to
        // compute the overall size of strings
        StringColumnStatistics scs = (StringColumnStatistics) child.fileStatistics;
        numVals = numVals == 0 ? 1 : numVals;
        int avgStringLen = (int) (scs.getSum() / numVals);
        return numVals * JavaDataModel.get().lengthForStringOfLength(avgStringLen);
      case DECIMAL:
        return numVals * JavaDataModel.get().lengthOfDecimal();
      case DATE:
        return numVals * JavaDataModel.get().lengthOfDate();
      case BINARY:
        // get total length of binary blob
        BinaryColumnStatistics bcs = (BinaryColumnStatistics) child.fileStatistics;
        return bcs.getSum();
      case TIMESTAMP:
        return numVals * JavaDataModel.get().lengthOfTimestamp();
      case LIST:
      case MAP:
      case UNION:
      case STRUCT: {
        TreeWriter[] childWriters = child.getChildrenWriters();
        List<TypeDescription> childTypes = schema.getChildren();
        for (int i=0; i < childWriters.length; ++i) {
          total += getRawDataSize(childWriters[i], childTypes.get(i));
        }
        break;
      }
      default:
        LOG.debug("Unknown object inspector category.");
        break;
    }
    return total;
  }

  private OrcProto.CompressionKind writeCompressionKind(CompressionKind kind) {
    switch (kind) {
      case NONE: return OrcProto.CompressionKind.NONE;
      case ZLIB: return OrcProto.CompressionKind.ZLIB;
      case SNAPPY: return OrcProto.CompressionKind.SNAPPY;
      case LZO: return OrcProto.CompressionKind.LZO;
      default:
        throw new IllegalArgumentException("Unknown compression " + kind);
    }
  }

  private void writeFileStatistics(OrcProto.Footer.Builder builder,
                                   TreeWriter writer) throws IOException {
    builder.addStatistics(writer.fileStatistics.serialize());
    for(TreeWriter child: writer.getChildrenWriters()) {
      writeFileStatistics(builder, child);
    }
  }

  private int writeMetadata() throws IOException {
    getStream();
    OrcProto.Metadata.Builder builder = OrcProto.Metadata.newBuilder();
    for(OrcProto.StripeStatistics.Builder ssb : treeWriter.stripeStatsBuilders) {
      builder.addStripeStats(ssb.build());
    }

    long startPosn = rawWriter.getPos();
    OrcProto.Metadata metadata = builder.build();
    metadata.writeTo(protobufWriter);
    protobufWriter.flush();
    writer.flush();
    return (int) (rawWriter.getPos() - startPosn);
  }

  private int writeFooter(long bodyLength) throws IOException {
    getStream();
    OrcProto.Footer.Builder builder = OrcProto.Footer.newBuilder();
    builder.setContentLength(bodyLength);
    builder.setHeaderLength(headerLength);
    builder.setNumberOfRows(rowCount);
    builder.setRowIndexStride(rowIndexStride);
    // populate raw data size
    rawDataSize = computeRawDataSize();
    // serialize the types
    writeTypes(builder, schema);
    // add the stripe information
    for(OrcProto.StripeInformation stripe: stripes) {
      builder.addStripes(stripe);
    }
    // add the column statistics
    writeFileStatistics(builder, treeWriter);
    // add all of the user metadata
    for(Map.Entry<String, ByteString> entry: userMetadata.entrySet()) {
      builder.addMetadata(OrcProto.UserMetadataItem.newBuilder()
          .setName(entry.getKey()).setValue(entry.getValue()));
    }
    long startPosn = rawWriter.getPos();
    OrcProto.Footer footer = builder.build();
    footer.writeTo(protobufWriter);
    protobufWriter.flush();
    writer.flush();
    return (int) (rawWriter.getPos() - startPosn);
  }

  private int writePostScript(int footerLength, int metadataLength) throws IOException {
    OrcProto.PostScript.Builder builder =
        OrcProto.PostScript.newBuilder()
            .setCompression(writeCompressionKind(compress))
            .setFooterLength(footerLength)
            .setMetadataLength(metadataLength)
            .setMagic(OrcFile.MAGIC)
            .addVersion(version.getMajor())
            .addVersion(version.getMinor())
            .setWriterVersion(OrcFile.CURRENT_WRITER.getId());
    if (compress != CompressionKind.NONE) {
      builder.setCompressionBlockSize(bufferSize);
    }
    OrcProto.PostScript ps = builder.build();
    // need to write this uncompressed
    long startPosn = rawWriter.getPos();
    ps.writeTo(rawWriter);
    long length = rawWriter.getPos() - startPosn;
    if (length > 255) {
      throw new IllegalArgumentException("PostScript too large at " + length);
    }
    return (int) length;
  }

  private long estimateStripeSize() {
    long result = 0;
    for(BufferedStream stream: streams.values()) {
      result += stream.getBufferSize();
    }
    result += treeWriter.estimateMemory();
    return result;
  }

  @Override
  public void addUserMetadata(String name, ByteBuffer value) {
    userMetadata.put(name, ByteString.copyFrom(value));
  }

  public void addTuple(Tuple tuple) throws IOException {
    ((StructTreeWriter)treeWriter).writeTuple(tuple);
    rowsInStripe += 1;
    if (buildIndex) {
      rowsInIndex += 1;

      if (rowsInIndex >= rowIndexStride) {
        createRowIndexEntry();
      }
    }
    memoryManager.addedRow(1);
  }

  @Override
  public void close() throws IOException {
    if (callback != null) {
      callback.preFooterWrite(callbackContext);
    }
    // remove us from the memory manager so that we don't get any callbacks
    memoryManager.removeWriter(path);
    // actually close the file
    flushStripe();
    int metadataLength = writeMetadata();
    int footerLength = writeFooter(rawWriter.getPos() - metadataLength);
    rawWriter.writeByte(writePostScript(footerLength, metadataLength));
    rawWriter.close();
  }

  /**
   * Raw data size will be compute when writing the file footer. Hence raw data
   * size value will be available only after closing the writer.
   */
  @Override
  public long getRawDataSize() {
    return rawDataSize;
  }

  /**
   * Row count gets updated when flushing the stripes. To get accurate row
   * count call this method after writer is closed.
   */
  @Override
  public long getNumberOfRows() {
    return rowCount;
  }

  @Override
  public long writeIntermediateFooter() throws IOException {
    // flush any buffered rows
    flushStripe();
    // write a footer
    if (stripesAtLastFlush != stripes.size()) {
      if (callback != null) {
        callback.preFooterWrite(callbackContext);
      }
      int metaLength = writeMetadata();
      int footLength = writeFooter(rawWriter.getPos() - metaLength);
      rawWriter.writeByte(writePostScript(footLength, metaLength));
      stripesAtLastFlush = stripes.size();
      rawWriter.hflush();
    }
    return rawWriter.getPos();
  }

  @Override
  public void appendStripe(byte[] stripe, int offset, int length,
                           StripeInformation stripeInfo,
                           OrcProto.StripeStatistics stripeStatistics) throws IOException {
    checkArgument(stripe != null, "Stripe must not be null");
    checkArgument(length <= stripe.length,
        "Specified length must not be greater specified array length");
    checkArgument(stripeInfo != null, "Stripe information must not be null");
    checkArgument(stripeStatistics != null,
        "Stripe statistics must not be null");

    getStream();
    long start = rawWriter.getPos();
    long availBlockSpace = blockSize - (start % blockSize);

    // see if stripe can fit in the current hdfs block, else pad the remaining
    // space in the block
    if (length < blockSize && length > availBlockSpace &&
        addBlockPadding) {
      byte[] pad = new byte[(int) Math.min(HDFS_BUFFER_SIZE, availBlockSpace)];
      LOG.info(String.format("Padding ORC by %d bytes while merging..",
          availBlockSpace));
      start += availBlockSpace;
      while (availBlockSpace > 0) {
        int writeLen = (int) Math.min(availBlockSpace, pad.length);
        rawWriter.write(pad, 0, writeLen);
        availBlockSpace -= writeLen;
      }
    }

    rawWriter.write(stripe);
    rowsInStripe = stripeStatistics.getColStats(0).getNumberOfValues();
    rowCount += rowsInStripe;

    // since we have already written the stripe, just update stripe statistics
    treeWriter.stripeStatsBuilders.add(stripeStatistics.toBuilder());

    // update file level statistics
    updateFileStatistics(stripeStatistics);

    // update stripe information
    OrcProto.StripeInformation dirEntry = OrcProto.StripeInformation
        .newBuilder()
        .setOffset(start)
        .setNumberOfRows(rowsInStripe)
        .setIndexLength(stripeInfo.getIndexLength())
        .setDataLength(stripeInfo.getDataLength())
        .setFooterLength(stripeInfo.getFooterLength())
        .build();
    stripes.add(dirEntry);

    // reset it after writing the stripe
    rowsInStripe = 0;
  }

  private void updateFileStatistics(OrcProto.StripeStatistics stripeStatistics) {
    List<OrcProto.ColumnStatistics> cs = stripeStatistics.getColStatsList();
    List<TreeWriter> allWriters = getAllColumnTreeWriters(treeWriter);
    for (int i = 0; i < allWriters.size(); i++) {
      allWriters.get(i).fileStatistics.merge(ColumnStatisticsImpl.deserialize(cs.get(i)));
    }
  }

  private List<TreeWriter> getAllColumnTreeWriters(TreeWriter rootTreeWriter) {
    List<TreeWriter> result = Lists.newArrayList();
    getAllColumnTreeWritersImpl(rootTreeWriter, result);
    return result;
  }

  private void getAllColumnTreeWritersImpl(TreeWriter tw,
                                           List<TreeWriter> result) {
    result.add(tw);
    for (TreeWriter child : tw.childrenWriters) {
      getAllColumnTreeWritersImpl(child, result);
    }
  }

  @Override
  public void appendUserMetadata(List<OrcProto.UserMetadataItem> userMetadata) {
    if (userMetadata != null) {
      for (OrcProto.UserMetadataItem item : userMetadata) {
        this.userMetadata.put(item.getName(), item.getValue());
      }
    }
  }
}
