package org.elasticsearch.river.hbase;

import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.charset.Charset;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;
import org.elasticsearch.search.facet.FacetBuilders;
import org.elasticsearch.search.facet.statistical.StatisticalFacet;
import org.hbase.async.HBaseClient;
import org.hbase.async.KeyValue;
import org.hbase.async.Scanner;

/**
 * An HBase import river build similar to the MySQL river, that was modeled after the Solr SQL import functionality.
 * 
 * @author Ravi Gairola
 */
public class HBaseRiver extends AbstractRiverComponent implements River, UncaughtExceptionHandler {
	private static final Charset	charset	= Charset.forName("UTF-8");
	private final Client			esClient;
	private volatile Thread			parser;

	/**
	 * Comma separated list of Zookeeper hosts to which the HBase client can connect to find the cluster.
	 */
	private final String			hosts;

	/**
	 * The HBase table name to be imported from.
	 */
	private final String			table;

	/**
	 * The ElasticSearch index name to be imported to. (default is the river name)
	 */
	private final String			index;

	/**
	 * The ElasticSearch type name to be imported to. (Default is the source table name)
	 */
	private final String			type;

	/**
	 * The interval in ms with which the river is supposed to run (60000 = every minute). (Default is every 10 minutes)
	 */
	private final long				interval;

	/**
	 * How big are the ElasticSearch bulk indexing sizes supposed to be. Tweaking this might improve performance. (Default is
	 * 100 operations)
	 */
	private final int				batchSize;

	/**
	 * Name of the field from HBase to be used as an idField in ElasticSearch. The mapping will set up accordingly, so that
	 * the _id field is routed to this field name (you can access it then under both the field name and "_id"). If no id
	 * field is given, then ElasticSearch will automatically generate an id.
	 */
	private final String			idField;

	/**
	 * Loads and verifies all the configuration needed to run this river.
	 * 
	 * @param riverName
	 * @param settings
	 * @param esClient
	 */
	@Inject
	public HBaseRiver(final RiverName riverName, final RiverSettings settings, final Client esClient) {
		super(riverName, settings);
		this.esClient = esClient;
		this.logger.info("Creating HBase Stream River");

		this.hosts = readConfig("hosts");
		this.table = readConfig("table");
		this.idField = readConfig("idField", null);
		this.index = readConfig("index", riverName.name());
		this.type = readConfig("type", this.table);
		this.interval = Long.parseLong(readConfig("interval", "600000"));
		this.batchSize = Integer.parseInt(readConfig("batchSize", "100"));

		if (this.interval <= 0) {
			throw new IllegalArgumentException("The interval between runs must be at least 1 ms. The current config is set to "
					+ this.interval);
		}
		if (this.batchSize <= 0) {
			throw new IllegalArgumentException("The batch size must be set to at least 1. The current config is set to " + this.batchSize);
		}
	}

	/**
	 * Fetch the value of a configuration that has no default value and is therefore mandatory. Empty (trimmed) strings are
	 * as invalid as no value at all (null).
	 * 
	 * @param config Key of the configuration to fetch
	 * @throws InvalidParameterException if a configuration is missing (null or empty)
	 * @return
	 */
	private String readConfig(final String config) {
		final String result = readConfig(config, null);
		if (result == null || result.trim().isEmpty()) {
			this.logger.error("Unable to read required config {}. Aborting!", config);
			throw new InvalidParameterException("Unable to read required config " + config);
		}
		return result;
	}

	/**
	 * Fetch the value of a configuration that has a default value and is therefore optional.
	 * 
	 * @param config Key of the configuration to fetch
	 * @param defaultValue The value to set if no value could be found
	 * @return
	 */
	@SuppressWarnings({ "unchecked" })
	private String readConfig(final String config, final String defaultValue) {
		if (this.settings.settings().containsKey("hbase")) {
			Map<String, Object> mysqlSettings = (Map<String, Object>) this.settings.settings().get("hbase");
			return XContentMapValues.nodeStringValue(mysqlSettings.get(config), defaultValue);
		}
		return defaultValue;
	}

	/**
	 * This method is launched by ElasticSearch and starts the HBase River. The method will try to create a mapping with time
	 * stamps enabled. If a mapping already exists the user should make sure, that time stamps are enabled for this type.
	 */
	@Override
	public synchronized void start() {
		if (this.parser != null) {
			this.logger.warn("Trying to start HBase stream although it is already running");
			return;
		}
		this.logger.info("Starting HBase Stream");
		String mapping;
		if (this.idField == null) {
			mapping = "{\"" + this.type + "\":{\"_timestamp\":{\"enabled\":true}}}";
		}
		else {
			mapping = "{\"" + this.type + "\":{\"_timestamp\":{\"enabled\":true},\"_id\":{\"path\":\"" + this.idField + "\"}}}";
		}

		try {
			this.esClient.admin().indices().prepareCreate(this.index).addMapping(this.type, mapping).execute().actionGet();
			this.logger.info("Created Index {} with _timestamp mapping for {}", this.index, this.type);
		} catch (Exception e) {
			if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
				this.logger.debug("Not creating Index {} as it already exists", this.index);
			}
			else if (ExceptionsHelper.unwrapCause(e) instanceof ElasticSearchException) {
				this.logger.debug("Mapping {}.{} already exists and will not be created", this.index, this.type);
			}
			else {
				this.logger.warn("failed to create index [{}], disabling river...", e, this.index);
				return;
			}
		}

		try {
			this.esClient.admin()
				.indices()
				.preparePutMapping(this.index)
				.setType(this.type)
				.setSource(mapping)
				.setIgnoreConflicts(true)
				.execute()
				.actionGet();
		} catch (ElasticSearchException e) {
			this.logger.debug("Mapping already exists for index {} and type {}", this.index, this.type);
		}

		this.parser = new Parser();
		final Thread t = EsExecutors.daemonThreadFactory(this.settings.globalSettings(), "hbase_slurper").newThread(this.parser);
		t.setUncaughtExceptionHandler(this);
		t.start();
	}

	/**
	 * This method is called by ElasticSearch when shutting down the river. The method will stop the thread and close all
	 * connections to HBase.
	 */
	@Override
	public void close() {
		this.logger.info("Closing HBase river");
		if (this.parser instanceof Parser) {
			((Parser) this.parser).stopThread();
		}
		this.parser = null;
	}

	/**
	 * Some of the asynchronous methods of the HBase client will throw Exceptions that are not caught anywhere else. If this
	 * happens, the entire river is shut down.
	 */
	@Override
	public void uncaughtException(final Thread arg0, final Throwable arg1) {
		this.logger.error("An Exception has been thrown in HBase Import Thread", arg1, (Object[]) null);
		close();
	}

	/**
	 * A separate Thread that does the actual fetching and storing of data from an HBase cluster.
	 * 
	 * @author Ravi Gairola
	 */
	private class Parser extends Thread implements ActionListener<BulkResponse> {
		private static final String	TIMESTMAP_STATS	= "timestamp_stats";
		private int					indexCounter;
		private HBaseClient			client;
		private Scanner				scanner;
		private boolean				stopThread;

		/**
		 * Timing mechanism of the thread that determines when a parse operation is supposed to run. Waits for the predefined
		 * interval until a new run is performed. The method checks every 1000ms if it should be parsing again. The first run
		 * is done immediately once the thread is started.
		 */
		@Override
		public void run() {
			HBaseRiver.this.logger.info("HBase Import Thread has started");
			long lastRun = 0;
			while (!this.stopThread) {
				if (lastRun + HBaseRiver.this.interval < System.currentTimeMillis()) {
					lastRun = System.currentTimeMillis();
					try {
						parse();
					} catch (Exception e) {
						HBaseRiver.this.logger.error("An exception has been caught while parsing data from HBase", e);
					}
					if (!this.stopThread) {
						HBaseRiver.this.logger.info("HBase Import Thread is waiting for {} Seconds until the next run",
							HBaseRiver.this.interval / 1000);
					}
				}
				try {
					sleep(1000);
				} catch (InterruptedException e) {
					HBaseRiver.this.logger.trace("HBase river parsing thread has been interrupted");
				}
			}
			HBaseRiver.this.logger.info("HBase Import Thread has finished");
		}

		/**
		 * The actual parse implementation that connects to the HBase cluster and fetches all rows since the last import.
		 * Fetched rows are added to an ElasticSearch Bulk Request with a size according to batchSize (default is 100).
		 * 
		 * @throws InterruptedException
		 * @throws Exception
		 */
		private void parse() throws InterruptedException, Exception {
			HBaseRiver.this.logger.info("Parsing data from HBase");
			this.client = new HBaseClient(HBaseRiver.this.hosts);
			try {
				HBaseRiver.this.logger.debug("Checking if table {} actually exists in HBase DB", HBaseRiver.this.table);
				this.client.ensureTableExists(HBaseRiver.this.table);
				HBaseRiver.this.logger.debug("Fetching HBase Scanner");
				this.scanner = this.client.newScanner(HBaseRiver.this.table);
				final String timestamp = String.valueOf(setMinTimestamp(this.scanner));

				ArrayList<ArrayList<KeyValue>> rows;
				HBaseRiver.this.logger.debug("Starting to fetch rows");
				while ((rows = this.scanner.nextRows(HBaseRiver.this.batchSize).join()) != null) {
					if (this.stopThread) {
						HBaseRiver.this.logger.info("Stopping HBase import in the midle of it");
						break;
					}
					parseBulkOfRows(timestamp, rows);
				}
			} finally {
				stopThread();
			}
		}

		/**
		 * Run over a bulk of rows and process them.
		 * 
		 * @param timestamp
		 * @param rows
		 */
		private void parseBulkOfRows(final String timestamp, final ArrayList<ArrayList<KeyValue>> rows) {
			HBaseRiver.this.logger.debug("Processing the next {} entries in HBase parsing process", rows.size());
			final BulkRequestBuilder bulkRequest = HBaseRiver.this.esClient.prepareBulk();
			for (final ArrayList<KeyValue> row : rows) {
				if (this.stopThread) {
					HBaseRiver.this.logger.info("Stopping HBase import in the midle of it");
					break;
				}
				final IndexRequestBuilder request = HBaseRiver.this.esClient.prepareIndex(HBaseRiver.this.index, HBaseRiver.this.type);
				request.setTimestamp(timestamp);
				request.setSource(readDataTree(row));
				if (HBaseRiver.this.idField == null && row.size() > 0) {
					request.setId(new String(row.get(0).key(), charset));
				}
				bulkRequest.add(request);
			}
			bulkRequest.execute().addListener((ActionListener<BulkResponse>) this);
			HBaseRiver.this.logger.debug("Sent Bulk Request with HBase data to ElasticSearch");
		}

		/**
		 * Generate a tree structure that ElasticSearch can read and index from one of the rows that has been returned from
		 * HBase.
		 * 
		 * @param row
		 * @return
		 */
		@SuppressWarnings("unchecked")
		private Map<String, Object> readDataTree(final ArrayList<KeyValue> row) {
			final Map<String, Object> dataTree = new HashMap<String, Object>();
			for (final KeyValue column : row) {
				final String family = new String(column.family(), charset);
				final String qualifier = new String(column.qualifier(), charset);
				final String value = new String(column.value(), charset);
				if (!dataTree.containsKey(family)) {
					dataTree.put(family, new HashMap<String, Object>());
				}
				((Map<String, String>) dataTree.get(family)).put(qualifier, value);
			}
			return dataTree;
		}

		/**
		 * Checks if there is an open Scanner or Client and closes them.
		 */
		public synchronized void stopThread() {
			this.stopThread = true;
			if (this.scanner != null) {
				try {
					this.scanner.close();
				} catch (Exception e) {
					HBaseRiver.this.logger.error("An Exception has been caught while closing the HBase Scanner", e, (Object[]) null);
				}
			}
			if (this.client != null) {
				try {
					this.client.shutdown();
				} catch (Exception e) {
					HBaseRiver.this.logger.error("An Exception has been caught while shuting down the HBase client", e, (Object[]) null);
				}
			}
		}

		/**
		 * Sets the minimum time stamp on the HBase scanner, by looking into Elasticsearch for the last entry made.
		 * 
		 * @param scanner
		 */
		private long setMinTimestamp(final Scanner scanner) {
			HBaseRiver.this.logger.debug("Looking into ElasticSearch to determine timestamp of last import");
			final SearchResponse response = HBaseRiver.this.esClient.prepareSearch(HBaseRiver.this.index)
				.setTypes(HBaseRiver.this.type)
				.setQuery(QueryBuilders.matchAllQuery())
				.addFacet(FacetBuilders.statisticalFacet(TIMESTMAP_STATS).field("_timestamp"))
				.execute()
				.actionGet();

			if (response.facets().facet(TIMESTMAP_STATS) != null) {
				HBaseRiver.this.logger.debug("Got statistical data from ElasticSearch about data timestamps");
				final StatisticalFacet facet = (StatisticalFacet) response.facets().facet(TIMESTMAP_STATS);
				final long timestamp = (long) Math.max(facet.getMax(), 0);
				scanner.setMinTimestamp(timestamp);
				return timestamp;
			}
			HBaseRiver.this.logger.debug("No statistical data about data timestamps could be found -> probably no data there yet");
			scanner.setMinTimestamp(0);
			return 0L;
		}

		/**
		 * Elasticsearch Response handler.
		 */
		@Override
		public void onResponse(final BulkResponse response) {
			this.indexCounter += response.items().length;
			HBaseRiver.this.logger.info("HBase imported has indexed {} entries so far", this.indexCounter);
			if (response.hasFailures()) {
				HBaseRiver.this.logger.error("Errors have occured while trying to index new data from HBase");
			}
		}

		/**
		 * Elasticsearch Failure handler.
		 */
		@Override
		public void onFailure(final Throwable e) {
			HBaseRiver.this.logger.error("An error has been caught while trying to index new data from HBase", e, new Object[] {});
		}
	}
}