package org.embulk.output.elasticsearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.ColumnVisitor;
import org.embulk.spi.Exec;
import org.embulk.spi.OutputPlugin;
import org.embulk.spi.Page;
import org.embulk.spi.PageReader;
import org.embulk.spi.Schema;
import org.embulk.spi.TransactionalPageOutput;
import org.embulk.spi.type.Types;
import org.slf4j.Logger;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.inject.Inject;

public class ElasticsearchOutputPlugin
        implements OutputPlugin
{
    public interface NodeAddressTask
            extends Task
    {
        @Config("host")
        public String getHost();

        @Config("port")
        @ConfigDefault("9300")
        public int getPort();
    }

    public interface PluginTask
            extends Task
    {
        @Config("version")
        @ConfigDefault("\"2\"")
        public String getVersion();

        @Config("nodes")
        public List<NodeAddressTask> getNodes();

        @Config("cluster_name")
        @ConfigDefault("\"elasticsearch\"")
        public String getClusterName();

        @Config("index")
        public String getIndex();

        @Config("index_type")
        public String getType();

        @Config("id")
        @ConfigDefault("null")
        public Optional<String> getId();

        @Config("bulk_actions")
        @ConfigDefault("1000")
        public int getBulkActions();

        @Config("bulk_size")
        @ConfigDefault("5242880")
        public long getBulkSize();

        @Config("concurrent_requests")
        @ConfigDefault("5")
        public int getConcurrentRequests();
    }

    private final Logger log;

    @Inject
    public ElasticsearchOutputPlugin()
    {
        log = Exec.getLogger(getClass());
    }

    @Override
    public ConfigDiff transaction(ConfigSource config, Schema schema,
                                  int processorCount, Control control)
    {
        final PluginTask task = config.loadConfig(PluginTask.class);
        // confirm that a client can be initialized
        try (Client client = createClient(task)) {
        }

        try {
            control.run(task.dump());
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }

        ConfigDiff nextConfig = Exec.newConfigDiff();
        return nextConfig;
    }

    @Override
    public  ConfigDiff resume(TaskSource taskSource,
                             Schema schema, int processorCount,
                             OutputPlugin.Control control)
    {
        //  TODO
        return Exec.newConfigDiff();
    }

    @Override
    public void cleanup(TaskSource taskSource,
                        Schema schema, int processorCount,
                        List<TaskReport> successTaskReports)
    { }

    private Client createClient(final PluginTask task)
    {
        String version = task.getVersion();
        Client client = null;
        Class<?> clazz = null;
        Object obj = null;
        try {
            switch (version) {
                case "1":
                    // version 1.x
                    clazz = Class.forName("org.embulk.output.elasticsearch.ElasticsearchOutputPlugin_1x");
                    break;
                default:
                    // version 2.x
                    clazz = Class.forName("org.embulk.output.elasticsearch.ElasticsearchOutputPlugin_2x");
            }
            obj = clazz.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            Throwables.propagate(e);
        }

        try {
            log.info(String.format("creating TransportClient for Elasticsearch %s", version));
            Method method = clazz.getMethod("createClient", String.class, List.class);
            client = (Client) method.invoke(obj, task.getClusterName(), getNodeList(task.getNodes()));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            Throwables.propagate(e);
        }
        return client;
    }

    // convert to List for ElasticsearchOutputPlugin_xx classes.
    private List<HashMap<String, String>> getNodeList(List<NodeAddressTask> nodes)
    {
        List<HashMap<String, String>> nodeList = new ArrayList<HashMap<String, String>>();
        for (NodeAddressTask node : nodes) {
            HashMap<String, String> nodeInfo= new HashMap<String, String>();
            nodeInfo.put("Host", node.getHost());
            nodeInfo.put("Port", String.valueOf(node.getPort()));
            nodeList.add(nodeInfo);
        }
        return nodeList;
    }

    private BulkProcessor newBulkProcessor(final PluginTask task, final Client client)
    {
        return BulkProcessor.builder(client, new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request)
            {
                log.info("Execute {} bulk actions", request.numberOfActions());
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response)
            {
                if (response.hasFailures()) {
                    long items = 0;
                    if (log.isDebugEnabled()) {
                        for (BulkItemResponse item : response.getItems()) {
                            if (item.isFailed()) {
                                items += 1;
                                log.debug("   Error for {}/{}/{} for {} operation: {}",
                                        item.getIndex(), item.getType(), item.getId(),
                                        item.getOpType(), item.getFailureMessage());
                            }
                        }
                    }
                    log.warn("{} bulk actions failed: {}", items, response.buildFailureMessage());
                } else {
                    log.info("{} bulk actions succeeded", request.numberOfActions());
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure)
            {
                log.warn("Got the error during bulk processing", failure);
            }
        }).setBulkActions(task.getBulkActions())
          .setBulkSize(new ByteSizeValue(task.getBulkSize()))
          .setConcurrentRequests(task.getConcurrentRequests())
          .build();
    }

    @Override
    public TransactionalPageOutput open(TaskSource taskSource, Schema schema,
                                        int processorIndex)
    {
        final PluginTask task = taskSource.loadTask(PluginTask.class);
        Client client = createClient(task);
        BulkProcessor bulkProcessor = newBulkProcessor(task, client);
        ElasticsearchPageOutput pageOutput = new ElasticsearchPageOutput(task, client, bulkProcessor);
        pageOutput.open(schema);
        return pageOutput;
    }

    public static class ElasticsearchPageOutput implements TransactionalPageOutput
    {
        private Logger log;

        private Client client;
        private BulkProcessor bulkProcessor;

        private PageReader pageReader;
        private Column idColumn;

        private final String index;
        private final String type;
        private final String id;

        public ElasticsearchPageOutput(PluginTask task, Client client, BulkProcessor bulkProcessor)
        {
            this.log = Exec.getLogger(getClass());

            this.client = client;
            this.bulkProcessor = bulkProcessor;

            this.index = task.getIndex();
            this.type = task.getType();
            this.id = task.getId().orNull();
        }

        void open(final Schema schema)
        {
            pageReader = new PageReader(schema);
            idColumn = (id == null) ? null : schema.lookupColumn(id);
        }

        @Override
        public void add(Page page)
        {
            pageReader.setPage(page);

            while (pageReader.nextRecord()) {
                try {
                    final XContentBuilder contextBuilder = XContentFactory.jsonBuilder().startObject(); //  TODO reusable??
                    pageReader.getSchema().visitColumns(new ColumnVisitor() {
                        @Override
                        public void booleanColumn(Column column) {
                            try {
                                if (pageReader.isNull(column)) {
                                    contextBuilder.nullField(column.getName());
                                } else {
                                    contextBuilder.field(column.getName(), pageReader.getBoolean(column));
                                }
                            } catch (IOException e) {
                                try {
                                    contextBuilder.nullField(column.getName());
                                } catch (IOException ex) {
                                    throw Throwables.propagate(ex);
                                }
                            }
                        }

                        @Override
                        public void longColumn(Column column) {
                            try {
                                if (pageReader.isNull(column)) {
                                    contextBuilder.nullField(column.getName());
                                } else {
                                    contextBuilder.field(column.getName(), pageReader.getLong(column));
                                }
                            } catch (IOException e) {
                                try {
                                    contextBuilder.nullField(column.getName());
                                } catch (IOException ex) {
                                    throw Throwables.propagate(ex);
                                }
                            }
                        }

                        @Override
                        public void doubleColumn(Column column) {
                            try {
                                if (pageReader.isNull(column)) {
                                    contextBuilder.nullField(column.getName());
                                } else {
                                    contextBuilder.field(column.getName(), pageReader.getDouble(column));
                                }
                            } catch (IOException e) {
                                try {
                                    contextBuilder.nullField(column.getName());
                                } catch (IOException ex) {
                                    throw Throwables.propagate(ex);
                                }
                            }
                        }

                        @Override
                        public void stringColumn(Column column) {
                            try {
                                if (pageReader.isNull(column)) {
                                    contextBuilder.nullField(column.getName());
                                } else {
                                    contextBuilder.field(column.getName(), pageReader.getString(column));
                                }
                            } catch (IOException e) {
                                try {
                                    contextBuilder.nullField(column.getName());
                                } catch (IOException ex) {
                                    throw Throwables.propagate(ex);
                                }
                            }
                        }

                        @Override
                        public void timestampColumn(Column column) {
                            try {
                                if (pageReader.isNull(column)) {
                                    contextBuilder.nullField(column.getName());
                                } else {
                                    contextBuilder.field(column.getName(), new Date(pageReader.getTimestamp(column).toEpochMilli()));
                                }
                            } catch (IOException e) {
                                try {
                                    contextBuilder.nullField(column.getName());
                                } catch (IOException ex) {
                                    throw Throwables.propagate(ex);
                                }
                            }
                        }
                    });

                    contextBuilder.endObject();
                    bulkProcessor.add(newIndexRequest(getIdValue(idColumn)).source(contextBuilder));

                } catch (IOException e) {
                    Throwables.propagate(e); //  TODO error handling
                }
            }
        }

        /**
         * @param inputColumn
         * @return
         */
        private String getIdValue(Column inputColumn) {
            if (inputColumn == null) return null;
            if (pageReader.isNull(inputColumn)) return null;
            String idValue = null;
            if (Types.STRING.equals(inputColumn.getType())) {
                idValue = pageReader.getString(inputColumn);
            } else if (Types.BOOLEAN.equals(inputColumn.getType())) {
                idValue = pageReader.getBoolean(inputColumn) + "";
            } else if (Types.DOUBLE.equals(inputColumn.getType())) {
                idValue = pageReader.getDouble(inputColumn) + "";
            } else if (Types.LONG.equals(inputColumn.getType())) {
                idValue = pageReader.getLong(inputColumn) + "";
            } else if (Types.TIMESTAMP.equals(inputColumn.getType())) {
                idValue = pageReader.getTimestamp(inputColumn).toString();
            } else {
                idValue = null;
            }
            return idValue;
        }

        private IndexRequest newIndexRequest(String idValue)
        {
            return Requests.indexRequest(index).type(type).id(idValue);
        }

        @Override
        public void finish()
        {
            try {
                bulkProcessor.flush();
            } finally {
                close();
            }
        }

        @Override
        public void close()
        {
            if (bulkProcessor != null) {
                try {
                    while (!bulkProcessor.awaitClose(3, TimeUnit.SECONDS)) {
                        log.debug("wait for closing the bulk processing..");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                bulkProcessor = null;
            }

            if (client != null) {
                client.close(); //  ElasticsearchException
                client = null;
            }
        }

        @Override
        public void abort()
        {
            //  TODO do nothing
        }

        @Override
        public TaskReport commit()
        {
            TaskReport report = Exec.newTaskReport();
            //  TODO
            return report;
        }

    }
}
