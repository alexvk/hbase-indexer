/*
 * Copyright 2013 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ngdata.hbaseindexer.mr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.ngdata.hbaseindexer.conf.IndexerConf;
import com.ngdata.hbaseindexer.conf.IndexerConf.RowReadMode;
import com.ngdata.hbaseindexer.conf.IndexerConfBuilder;
import com.ngdata.hbaseindexer.conf.XmlIndexerConfReader;
import com.ngdata.hbaseindexer.indexer.Indexer;
import com.ngdata.hbaseindexer.indexer.ResultToSolrMapperFactory;
import com.ngdata.hbaseindexer.indexer.ResultWrappingRowData;
import com.ngdata.hbaseindexer.indexer.RowData;
import com.ngdata.hbaseindexer.indexer.SolrInputDocumentWriter;
import com.ngdata.hbaseindexer.parse.ResultToSolrMapper;
import com.ngdata.sep.util.io.Closer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an indexer over HBase data, printing the output to a PrintStream.
 * <p>
 * The purpose of this class is to allow for quickly debugging indexing
 * before actually writing it to
 */
public class IndexerDryRun {
    
    private static final Logger LOG = LoggerFactory.getLogger(IndexerDryRun.class);

    private HBaseIndexingOptions indexingOpts;
    private Configuration hbaseConf;
    private SolrInputDocumentWriter documentWriter;

    /**
     * @param hbaseIndexingOpts definition of the indexing job to be run
     * @param hbaseConf contains information for connecting to HBase
     * @param output output stream to print index documents
     */
    public IndexerDryRun(HBaseIndexingOptions hbaseIndexingOpts, Configuration hbaseConf, OutputStream output) {
        this.indexingOpts = hbaseIndexingOpts;
        this.hbaseConf = hbaseConf;
        this.documentWriter = new DryRunSolrInputDocumentWriter(output);
    }
    
    /**
     * Run a dry run over the configured input, printing SolrInputDocuments to
     * the configured output.
     * 
     * @return 0 if successful, non-zero otherwise
     */
    int run() {
        IndexingSpecification indexingSpec = indexingOpts.getIndexingSpecification();
        
        IndexerConf indexerConf;
        try {
            indexerConf = new XmlIndexerConfReader().read(new ByteArrayInputStream(
                    indexingSpec.getIndexConfigXml().getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        if (indexerConf.getRowReadMode() != RowReadMode.NEVER) {
            LOG.warn("Changing row read mode from " + indexerConf.getRowReadMode() + " to " + RowReadMode.NEVER);
            indexerConf = new IndexerConfBuilder(indexerConf).rowReadMode(RowReadMode.NEVER).build();
        }
        
        ResultToSolrMapper resultToSolrMapper = ResultToSolrMapperFactory.createResultToSolrMapper(
                                                        indexingSpec.getIndexerName(),
                                                        indexerConf,
                                                        indexingSpec.getIndexConnectionParams());
        
        Indexer indexer = Indexer.createIndexer(
                                indexingSpec.getIndexerName(),
                                indexerConf,
                                indexingSpec.getTableName(),
                                resultToSolrMapper,
                                null,
                                documentWriter);
        
        Scan scan = indexingOpts.getScan();
        
        HTable htable = null;
        try {
            htable = new HTable(hbaseConf, indexingSpec.getTableName());
            ResultScanner scanner = htable.getScanner(scan);
            for (Result result : scanner) {
                indexer.indexRowData(ImmutableList.<RowData>of(new ResultWrappingRowData(result)));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            Closer.close(htable);
        }
        
        
        return 0;
        
    }
    
    
    static class DryRunSolrInputDocumentWriter implements SolrInputDocumentWriter {
        
        private PrintWriter printWriter;

        public DryRunSolrInputDocumentWriter(OutputStream outputStream) {
            this.printWriter = new PrintWriter(outputStream, true);
        }

        @Override
        public void add(Map<String, SolrInputDocument> inputDocumentMap) throws SolrServerException, IOException {
            for (SolrInputDocument doc : inputDocumentMap.values()) {
                printWriter.println("dryRun: " + doc);
            }
        }

        @Override
        public void deleteById(List<String> idsToDelete) throws SolrServerException, IOException {
            throw new UnsupportedOperationException("Deletes are not supported in batch mode");
        }

        @Override
        public void deleteByQuery(String deleteQuery) throws SolrServerException, IOException {
            throw new UnsupportedOperationException("Deletes are not supported in batch mode");
        }

        @Override
        public void close() throws SolrServerException, IOException {
        }
        
    }

}
