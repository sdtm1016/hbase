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
import java.util.Random;
import java.util.TreeMap;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.dfs.MiniDFSCluster;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseAdmin;
import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HScannerInterface;
import org.apache.hadoop.hbase.HStoreKey;
import org.apache.hadoop.hbase.HTable;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.MultiRegionTable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiSearcher;
import org.apache.lucene.search.Searchable;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.TermQuery;

/**
 * Test Map/Reduce job to build index over HBase table
 */
public class TestTableIndex extends HBaseTestCase {
  private static final Log LOG = LogFactory.getLog(TestTableIndex.class);

  static final String TABLE_NAME = "moretest";
  static final String INPUT_COLUMN = "contents:";
  static final Text TEXT_INPUT_COLUMN = new Text(INPUT_COLUMN);
  static final String OUTPUT_COLUMN = "text:";
  static final Text TEXT_OUTPUT_COLUMN = new Text(OUTPUT_COLUMN);
  static final String ROWKEY_NAME = "key";
  static final String INDEX_DIR = "testindex";

  private HTableDescriptor desc;

  private MiniDFSCluster dfsCluster = null;
  private FileSystem fs;
  private Path dir;
  private MiniHBaseCluster hCluster = null;

  @Override
  public void setUp() throws Exception {
    super.setUp();

    // This size should make it so we always split using the addContent
    // below. After adding all data, the first region is 1.3M
    conf.setLong("hbase.hregion.max.filesize", 256 * 1024);

    desc = new HTableDescriptor(TABLE_NAME);
    desc.addFamily(new HColumnDescriptor(INPUT_COLUMN));
    desc.addFamily(new HColumnDescriptor(OUTPUT_COLUMN));

    dfsCluster = new MiniDFSCluster(conf, 1, true, (String[]) null);
    try {
      fs = dfsCluster.getFileSystem();

      dir = new Path("/hbase");
      fs.mkdirs(dir);

      // Start up HBase cluster
      hCluster = new MiniHBaseCluster(conf, 1, dfsCluster);

      // Create a table.
      HBaseAdmin admin = new HBaseAdmin(conf);
      admin.createTable(desc);

      // Populate a table into multiple regions
      MultiRegionTable.makeMultiRegionTable(conf, hCluster, null, TABLE_NAME,
        INPUT_COLUMN);

      // Verify table indeed has multiple regions
      HTable table = new HTable(conf, new Text(TABLE_NAME));
      Text[] startKeys = table.getStartKeys();
      assertTrue(startKeys.length > 1);
    } catch (Exception e) {
      if (dfsCluster != null) {
        dfsCluster.shutdown();
        dfsCluster = null;
      }
      throw e;
    }
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();

    if (hCluster != null) {
      hCluster.shutdown();
    }

    if (dfsCluster != null) {
      dfsCluster.shutdown();
    }
  }

  /**
   * Test HBase map/reduce
   * 
   * @throws IOException
   */
  @SuppressWarnings("static-access")
  public void testTableIndex() throws IOException {
    long firstK = 32;
    LOG.info("Print table contents before map/reduce");
    scanTable(conf, firstK);

    @SuppressWarnings("deprecation")
    MiniMRCluster mrCluster = new MiniMRCluster(2, fs.getUri().toString(), 1);

    // set configuration parameter for index build
    conf.set("hbase.index.conf", createIndexConfContent());

    try {
      JobConf jobConf = new JobConf(conf, TestTableIndex.class);
      jobConf.setJobName("index column contents");
      jobConf.setNumMapTasks(2);
      // number of indexes to partition into
      jobConf.setNumReduceTasks(1);

      // use identity map (a waste, but just as an example)
      IdentityTableMap.initJob(TABLE_NAME, INPUT_COLUMN,
          IdentityTableMap.class, jobConf);

      // use IndexTableReduce to build a Lucene index
      jobConf.setReducerClass(IndexTableReduce.class);
      jobConf.setOutputPath(new Path(INDEX_DIR));
      jobConf.setOutputFormat(IndexOutputFormat.class);

      JobClient.runJob(jobConf);

    } finally {
      mrCluster.shutdown();
    }

    LOG.info("Print table contents after map/reduce");
    scanTable(conf, firstK);

    // verify index results
    verify(conf);
  }

  private String createIndexConfContent() {
    StringBuffer buffer = new StringBuffer();
    buffer.append("<configuration><column><property>" +
      "<name>hbase.column.name</name><value>" + INPUT_COLUMN +
      "</value></property>");
    buffer.append("<property><name>hbase.column.store</name> " +
      "<value>true</value></property>");
    buffer.append("<property><name>hbase.column.index</name>" +
      "<value>true</value></property>");
    buffer.append("<property><name>hbase.column.tokenize</name>" +
      "<value>false</value></property>");
    buffer.append("<property><name>hbase.column.boost</name>" +
      "<value>3</value></property>");
    buffer.append("<property><name>hbase.column.omit.norms</name>" +
      "<value>false</value></property></column>");
    buffer.append("<property><name>hbase.index.rowkey.name</name><value>" +
      ROWKEY_NAME + "</value></property>");
    buffer.append("<property><name>hbase.index.max.buffered.docs</name>" +
      "<value>500</value></property>");
    buffer.append("<property><name>hbase.index.max.field.length</name>" +
      "<value>10000</value></property>");
    buffer.append("<property><name>hbase.index.merge.factor</name>" +
      "<value>10</value></property>");
    buffer.append("<property><name>hbase.index.use.compound.file</name>" +
      "<value>true</value></property>");
    buffer.append("<property><name>hbase.index.optimize</name>" +
      "<value>true</value></property></configuration>");

    IndexConfiguration c = new IndexConfiguration();
    c.addFromXML(buffer.toString());
    return c.toString();
  }

  private void scanTable(Configuration c, long firstK) throws IOException {
    HTable table = new HTable(c, new Text(TABLE_NAME));
    Text[] columns = { TEXT_INPUT_COLUMN, TEXT_OUTPUT_COLUMN };
    HScannerInterface scanner = table.obtainScanner(columns,
        HConstants.EMPTY_START_ROW);
    long count = 0;
    try {
      HStoreKey key = new HStoreKey();
      TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();
      while (scanner.next(key, results)) {
        if (count < firstK)
          LOG.info("row: " + key.getRow());
        for (Map.Entry<Text, byte[]> e : results.entrySet()) {
          if (count < firstK)
            LOG.info(" column: " + e.getKey() + " value: "
                + new String(e.getValue(), HConstants.UTF8_ENCODING));
        }
        count++;
      }
    } finally {
      scanner.close();
    }
  }

  private void verify(Configuration c) throws IOException {
    Path localDir = new Path(this.testDir, "index_" +
      Integer.toString(new Random().nextInt()));
    this.fs.copyToLocalFile(new Path(INDEX_DIR), localDir);
    Path [] indexDirs = this.localFs.listPaths(new Path [] {localDir});
    Searcher searcher = null;
    HScannerInterface scanner = null;
    try {
      if (indexDirs.length == 1) {
        searcher = new IndexSearcher((new File(indexDirs[0].
          toUri())).getAbsolutePath());
      } else if (indexDirs.length > 1) {
        Searchable[] searchers = new Searchable[indexDirs.length];
        for (int i = 0; i < indexDirs.length; i++) {
          searchers[i] = new IndexSearcher((new File(indexDirs[i].
            toUri()).getAbsolutePath()));
        }
        searcher = new MultiSearcher(searchers);
      } else {
        throw new IOException("no index directory found");
      }

      HTable table = new HTable(c, new Text(TABLE_NAME));
      Text[] columns = { TEXT_INPUT_COLUMN, TEXT_OUTPUT_COLUMN };
      scanner = table.obtainScanner(columns, HConstants.EMPTY_START_ROW);

      HStoreKey key = new HStoreKey();
      TreeMap<Text, byte[]> results = new TreeMap<Text, byte[]>();

      IndexConfiguration indexConf = new IndexConfiguration();
      String content = c.get("hbase.index.conf");
      if (content != null) {
        indexConf.addFromXML(content);
      }
      String rowkeyName = indexConf.getRowkeyName();

      int count = 0;
      while (scanner.next(key, results)) {
        String value = key.getRow().toString();
        Term term = new Term(rowkeyName, value);
        int hitCount = searcher.search(new TermQuery(term)).length();
        assertEquals("check row " + value, 1, hitCount);
        count++;
      }
      int maxDoc = searcher.maxDoc();
      assertEquals("check number of rows", count, maxDoc);
    } finally {
      if (null != searcher)
        searcher.close();
      if (null != scanner)
        scanner.close();
    }
  }
  /**
   * @param args unused
   */
  public static void main(@SuppressWarnings("unused") String[] args) {
    TestRunner.run(new TestSuite(TestTableIndex.class));
  }
}