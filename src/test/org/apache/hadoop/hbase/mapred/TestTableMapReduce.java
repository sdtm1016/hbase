/**
 * Copyright 2007 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.mapred;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MultiRegionTable;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Scanner;
import org.apache.hadoop.hbase.io.BatchUpdate;
import org.apache.hadoop.hbase.io.Cell;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.io.RowResult;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;

/**
 * Test Map/Reduce job over HBase tables. The map/reduce process we're testing
 * on our tables is simple - take every row in the table, reverse the value of
 * a particular cell, and write it back to the table.
 */
public class TestTableMapReduce extends MultiRegionTable {
  private static final Log LOG =
    LogFactory.getLog(TestTableMapReduce.class.getName());

  static final String MULTI_REGION_TABLE_NAME = "mrtest";
  static final String INPUT_COLUMN = "contents:";
  static final String OUTPUT_COLUMN = "text:";
  
  private static final byte [][] columns = new byte [][] {
    Bytes.toBytes(INPUT_COLUMN),
    Bytes.toBytes(OUTPUT_COLUMN)
  };

  /** constructor */
  public TestTableMapReduce() {
    super(INPUT_COLUMN);
    desc = new HTableDescriptor(MULTI_REGION_TABLE_NAME);
    desc.addFamily(new HColumnDescriptor(INPUT_COLUMN));
    desc.addFamily(new HColumnDescriptor(OUTPUT_COLUMN));
  }

  /**
   * Pass the given key and processed record reduce
   */
  public static class ProcessContentsMapper
  extends MapReduceBase
  implements TableMap<ImmutableBytesWritable, BatchUpdate> {
    /**
     * Pass the key, and reversed value to reduce
     * @param key 
     * @param value 
     * @param output 
     * @param reporter 
     * @throws IOException 
     */
    public void map(ImmutableBytesWritable key, RowResult value,
      OutputCollector<ImmutableBytesWritable, BatchUpdate> output,
      Reporter reporter) 
    throws IOException {
      if (value.size() != 1) {
        throw new IOException("There should only be one input column");
      }
      byte [][] keys = value.keySet().toArray(new byte [value.size()][]);
      if(!Bytes.equals(keys[0], Bytes.toBytes(INPUT_COLUMN))) {
        throw new IOException("Wrong input column. Expected: " + INPUT_COLUMN
            + " but got: " + keys[0]);
      }

      // Get the original value and reverse it
      
      String originalValue =
        new String(value.get(keys[0]).getValue(), HConstants.UTF8_ENCODING);
      StringBuilder newValue = new StringBuilder();
      for(int i = originalValue.length() - 1; i >= 0; i--) {
        newValue.append(originalValue.charAt(i));
      }
      
      // Now set the value to be collected

      BatchUpdate outval = new BatchUpdate(key.get());
      outval.put(OUTPUT_COLUMN, Bytes.toBytes(newValue.toString()));
      output.collect(key, outval);
    }
  }
  
  /**
   * Test a map/reduce against a multi-region table
   * @throws IOException
   */
  public void testMultiRegionTable() throws IOException {
    runTestOnTable(new HTable(conf, MULTI_REGION_TABLE_NAME));
  }

  private void runTestOnTable(HTable table) throws IOException {
    MiniMRCluster mrCluster = new MiniMRCluster(2, fs.getUri().toString(), 1);

    JobConf jobConf = null;
    try {
      LOG.info("Before map/reduce startup");
      jobConf = new JobConf(conf, TestTableMapReduce.class);
      jobConf.setJobName("process column contents");
      jobConf.setNumReduceTasks(1);
      TableMapReduceUtil.initTableMapJob(Bytes.toString(table.getTableName()),
        INPUT_COLUMN, ProcessContentsMapper.class,
        ImmutableBytesWritable.class, BatchUpdate.class, jobConf);
      TableMapReduceUtil.initTableReduceJob(Bytes.toString(table.getTableName()),
        IdentityTableReduce.class, jobConf);
            
      LOG.info("Started " + Bytes.toString(table.getTableName()));
      JobClient.runJob(jobConf);
      LOG.info("After map/reduce completion");

      // verify map-reduce results
      verify(Bytes.toString(table.getTableName()));
    } finally {
      mrCluster.shutdown();
      if (jobConf != null) {
        FileUtil.fullyDelete(new File(jobConf.get("hadoop.tmp.dir")));
      }
    }
  }

  private void verify(String tableName) throws IOException {
    HTable table = new HTable(conf, tableName);
    boolean verified = false;
    long pause = conf.getLong("hbase.client.pause", 5 * 1000);
    int numRetries = conf.getInt("hbase.client.retries.number", 5);
    for (int i = 0; i < numRetries; i++) {
      try {
        LOG.info("Verification attempt #" + i);
        verifyAttempt(table);
        verified = true;
        break;
      } catch (NullPointerException e) {
        // If here, a cell was empty.  Presume its because updates came in
        // after the scanner had been opened.  Wait a while and retry.
        LOG.debug("Verification attempt failed: " + e.getMessage());
      }
      try {
        Thread.sleep(pause);
      } catch (InterruptedException e) {
        // continue
      }
    }
    assertTrue(verified);
  }

  /**
   * Looks at every value of the mapreduce output and verifies that indeed
   * the values have been reversed.
   * @param table Table to scan.
   * @throws IOException
   * @throws NullPointerException if we failed to find a cell value
   */
  private void verifyAttempt(final HTable table) throws IOException, NullPointerException {
    Scanner scanner =
      table.getScanner(columns, HConstants.EMPTY_START_ROW);
    try {
      for (RowResult r : scanner) {
        if (LOG.isDebugEnabled()) {
          if (r.size() > 2 ) {
            throw new IOException("Too many results, expected 2 got " +
              r.size());
          }
        }
        byte[] firstValue = null;
        byte[] secondValue = null;
        int count = 0;
        for(Map.Entry<byte [], Cell> e: r.entrySet()) {
          if (count == 0) {
            firstValue = e.getValue().getValue();
          }
          if (count == 1) {
            secondValue = e.getValue().getValue();
          }
          count++;
          if (count == 2) {
            break;
          }
        }
        
        String first = "";
        if (firstValue == null) {
          throw new NullPointerException(Bytes.toString(r.getRow()) +
            ": first value is null");
        }
        first = new String(firstValue, HConstants.UTF8_ENCODING);
        
        String second = "";
        if (secondValue == null) {
          throw new NullPointerException(Bytes.toString(r.getRow()) +
            ": second value is null");
        }
        byte[] secondReversed = new byte[secondValue.length];
        for (int i = 0, j = secondValue.length - 1; j >= 0; j--, i++) {
          secondReversed[i] = secondValue[j];
        }
        second = new String(secondReversed, HConstants.UTF8_ENCODING);

        if (first.compareTo(second) != 0) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("second key is not the reverse of first. row=" +
                r.getRow() + ", first value=" + first + ", second value=" +
                second);
          }
          fail();
        }
      }
    } finally {
      scanner.close();
    }
  }
}
