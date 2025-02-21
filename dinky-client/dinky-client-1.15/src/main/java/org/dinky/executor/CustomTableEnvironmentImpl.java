/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.executor;

import static org.apache.flink.table.api.bridge.internal.AbstractStreamTableEnvironmentImpl.lookupExecutor;

import org.dinky.data.exception.DinkyException;
import org.dinky.data.job.JobStatement;
import org.dinky.data.job.SqlType;
import org.dinky.data.model.LineageRel;
import org.dinky.data.result.SqlExplainResult;
import org.dinky.parser.CustomParserImpl;
import org.dinky.utils.LineageContext;
import org.dinky.utils.SqlUtil;

import org.apache.calcite.sql.SqlNode;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.runtime.jobgraph.jsonplan.JsonPlanGenerator;
import org.apache.flink.runtime.rest.messages.JobPlanInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.graph.JSONGenerator;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.ExplainDetail;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableConfig;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.internal.StreamTableEnvironmentImpl;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.api.internal.TableResultImpl;
import org.apache.flink.table.api.internal.TableResultInternal;
import org.apache.flink.table.catalog.CatalogManager;
import org.apache.flink.table.catalog.FunctionCatalog;
import org.apache.flink.table.catalog.GenericInMemoryCatalog;
import org.apache.flink.table.delegation.Executor;
import org.apache.flink.table.delegation.ExpressionParser;
import org.apache.flink.table.delegation.Planner;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.factories.PlannerFactoryUtil;
import org.apache.flink.table.module.ModuleManager;
import org.apache.flink.table.operations.CollectModifyOperation;
import org.apache.flink.table.operations.ModifyOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.QueryOperation;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.StrFormatter;

/**
 * CustomTableEnvironmentImpl
 *
 * @since 2022/05/08
 */
public class CustomTableEnvironmentImpl extends AbstractCustomTableEnvironment {

    private static final Logger log = LoggerFactory.getLogger(CustomTableEnvironmentImpl.class);

    private final CustomExtendedOperationExecutorImpl extendedExecutor = new CustomExtendedOperationExecutorImpl(this);
    private static final String UNSUPPORTED_QUERY_IN_EXECUTE_SQL_MSG =
            "Unsupported SQL query! executeSql() only accepts a single SQL statement of type "
                    + "CREATE TABLE, DROP TABLE, ALTER TABLE, CREATE DATABASE, DROP DATABASE, ALTER DATABASE, "
                    + "CREATE FUNCTION, DROP FUNCTION, ALTER FUNCTION, CREATE CATALOG, DROP CATALOG, "
                    + "USE CATALOG, USE [CATALOG.]DATABASE, SHOW CATALOGS, SHOW DATABASES, SHOW TABLES, SHOW [USER] FUNCTIONS, SHOW PARTITIONS"
                    + "CREATE VIEW, DROP VIEW, SHOW VIEWS, INSERT, DESCRIBE, LOAD MODULE, UNLOAD "
                    + "MODULE, USE MODULES, SHOW [FULL] MODULES.";

    public CustomTableEnvironmentImpl(
            CatalogManager catalogManager,
            ModuleManager moduleManager,
            FunctionCatalog functionCatalog,
            TableConfig tableConfig,
            StreamExecutionEnvironment executionEnvironment,
            Planner planner,
            Executor executor,
            boolean isStreamingMode,
            ClassLoader userClassLoader) {
        super(new StreamTableEnvironmentImpl(
                catalogManager,
                moduleManager,
                functionCatalog,
                tableConfig,
                executionEnvironment,
                planner,
                executor,
                isStreamingMode,
                userClassLoader));
        Thread.currentThread().setContextClassLoader(userClassLoader);
        injectParser(new CustomParserImpl(getPlanner().getParser()));
        injectExtendedExecutor(extendedExecutor);
    }

    public static CustomTableEnvironmentImpl create(
            StreamExecutionEnvironment executionEnvironment, ClassLoader classLoader) {
        return create(executionEnvironment, EnvironmentSettings.newInstance().build(), classLoader);
    }

    public static CustomTableEnvironmentImpl createBatch(
            StreamExecutionEnvironment executionEnvironment, ClassLoader classLoader) {
        Configuration configuration = new Configuration();
        configuration.set(ExecutionOptions.RUNTIME_MODE, RuntimeExecutionMode.BATCH);
        TableConfig tableConfig = new TableConfig();
        tableConfig.addConfiguration(configuration);
        return create(
                executionEnvironment,
                EnvironmentSettings.newInstance().inBatchMode().build(),
                classLoader);
    }

    public static CustomTableEnvironmentImpl create(
            StreamExecutionEnvironment executionEnvironment, EnvironmentSettings settings, ClassLoader classLoader) {

        // temporary solution until FLINK-15635 is fixed
        //        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        final Executor executor = lookupExecutor(classLoader, executionEnvironment);

        final TableConfig tableConfig = TableConfig.getDefault();
        tableConfig.setRootConfiguration(executor.getConfiguration());
        tableConfig.addConfiguration(settings.getConfiguration());

        final ModuleManager moduleManager = new ModuleManager();

        final CatalogManager catalogManager = CatalogManager.newBuilder()
                .classLoader(classLoader)
                .config(tableConfig)
                .defaultCatalog(
                        settings.getBuiltInCatalogName(),
                        new GenericInMemoryCatalog(settings.getBuiltInCatalogName(), settings.getBuiltInDatabaseName()))
                .executionConfig(executionEnvironment.getConfig())
                .build();

        final FunctionCatalog functionCatalog = new FunctionCatalog(tableConfig, catalogManager, moduleManager);

        final Planner planner =
                PlannerFactoryUtil.createPlanner(executor, tableConfig, moduleManager, catalogManager, functionCatalog);

        return new CustomTableEnvironmentImpl(
                catalogManager,
                moduleManager,
                functionCatalog,
                tableConfig,
                executionEnvironment,
                planner,
                executor,
                settings.isStreamingMode(),
                classLoader);
    }

    @Override
    public ObjectNode getStreamGraph(String statement) {
        List<Operation> operations = super.getParser().parse(statement);
        if (operations.size() != 1) {
            throw new TableException("Unsupported SQL query! explainSql() only accepts a single SQL query.");
        } else {
            List<ModifyOperation> modifyOperations = new ArrayList<>();
            for (int i = 0; i < operations.size(); i++) {
                if (operations.get(i) instanceof ModifyOperation) {
                    modifyOperations.add((ModifyOperation) operations.get(i));
                }
            }
            List<Transformation<?>> trans = getPlanner().translate(modifyOperations);
            for (Transformation<?> transformation : trans) {
                getStreamExecutionEnvironment().addOperator(transformation);
            }
            StreamGraph streamGraph = getStreamExecutionEnvironment().getStreamGraph();
            if (getConfig().getConfiguration().containsKey(PipelineOptions.NAME.key())) {
                streamGraph.setJobName(getConfig().getConfiguration().getString(PipelineOptions.NAME));
            }
            JSONGenerator jsonGenerator = new JSONGenerator(streamGraph);
            String json = jsonGenerator.getJSON();
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode objectNode = mapper.createObjectNode();
            try {
                objectNode = (ObjectNode) mapper.readTree(json);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            } finally {
                return objectNode;
            }
        }
    }

    @Override
    public <T> void addConfiguration(ConfigOption<T> option, T value) {
        Map<String, Object> flinkConfigurationMap = getFlinkConfigurationMap();
        getConfig().set(option, value);
        flinkConfigurationMap.put(option.key(), value);
    }

    private Map<String, Object> getFlinkConfigurationMap() {
        try {
            Configuration o = getRootConfiguration();
            Field confData = Configuration.class.getDeclaredField("confData");
            confData.setAccessible(true);
            Map<String, Object> temp = (Map<String, Object>) confData.get(o);
            return temp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public JobPlanInfo getJobPlanInfo(List<JobStatement> statements) {
        return new JobPlanInfo(JsonPlanGenerator.generatePlan(getJobGraphFromInserts(statements)));
    }

    @Override
    public StreamGraph getStreamGraphFromInserts(List<JobStatement> statements) {
        statements.removeIf(statement -> statement.getSqlType().equals(SqlType.CTAS));
        statements.removeIf(statement -> statement.getSqlType().equals(SqlType.RTAS));
        List<ModifyOperation> modifyOperations = new ArrayList<>();
        statements.stream()
                .map(statement -> getParser().parse(statement.getStatement()))
                .forEach(operations -> {
                    if (operations.size() != 1) {
                        throw new TableException("Only single statement is supported.");
                    }
                    Operation operation = operations.get(0);
                    if (operation instanceof ModifyOperation) {
                        modifyOperations.add((ModifyOperation) operation);
                    } else if (operation instanceof QueryOperation) {
                        modifyOperations.add(new CollectModifyOperation((QueryOperation) operation));
                    } else {
                        log.info("Only insert statement or select is supported now. The statement is skipped: "
                                + operation.asSummaryString());
                    }
                });
        if (modifyOperations.isEmpty()) {
            throw new TableException("Only insert statement is supported now. None operation to execute.");
        }
        return transOperatoinsToStreamGraph(modifyOperations);
    }

    private StreamGraph transOperatoinsToStreamGraph(List<ModifyOperation> modifyOperations) {
        List<Transformation<?>> trans = getPlanner().translate(modifyOperations);
        final StreamExecutionEnvironment environment = getStreamExecutionEnvironment();
        trans.forEach(environment::addOperator);

        StreamGraph streamGraph = environment.getStreamGraph();
        final Configuration configuration = getConfig().getConfiguration();
        if (configuration.containsKey(PipelineOptions.NAME.key())) {
            streamGraph.setJobName(configuration.getString(PipelineOptions.NAME));
        }
        return streamGraph;
    }

    @Override
    public SqlExplainResult explainSqlRecord(String statement, ExplainDetail... extraDetails) {
        List<Operation> operations = getParser().parse(statement);
        if (operations.size() != 1) {
            throw new TableException("Unsupported SQL query! explainSql() only accepts a single SQL query.");
        }

        Operation operation = operations.get(0);
        SqlExplainResult data = new SqlExplainResult();
        data.setParseTrue(true);
        data.setExplainTrue(true);
        if (operation instanceof ModifyOperation) {
            data.setType("DML");
        } else if (operation instanceof QueryOperation) {
            data.setType("DQL");
        } else {
            data.setExplain(operation.asSummaryString());
            data.setType("DDL");
            return data;
        }

        data.setExplain(getPlanner().explain(Collections.singletonList(operation), extraDetails));
        return data;
    }

    @Override
    public SqlNode parseSql(String sql) {
        return getParser().parseSql(sql);
    }

    @Override
    public <T> Table fromDataStream(DataStream<T> dataStream, String fields) {
        List<Expression> expressions = ExpressionParser.INSTANCE.parseExpressionList(fields);
        return fromDataStream(dataStream, expressions.toArray(new Expression[0]));
    }

    @Override
    public <T> void createTemporaryView(String path, DataStream<T> dataStream, String fields) {
        createTemporaryView(path, fromDataStream(dataStream, fields));
    }

    @Override
    public List<LineageRel> getLineage(String statement) {
        LineageContext lineageContext = new LineageContext((TableEnvironmentImpl) streamTableEnvironment);
        return lineageContext.analyzeLineage(statement);
    }

    @Override
    public <T> void createTemporaryView(String path, DataStream<T> dataStream, Expression... fields) {
        createTemporaryView(path, fromDataStream(dataStream, fields));
    }

    @Override
    public TableResult executeSql(String statement) {
        List<Operation> operations = getParser().parse(statement);

        if (operations.size() != 1) {
            throw new TableException(UNSUPPORTED_QUERY_IN_EXECUTE_SQL_MSG);
        }

        return executeInternal(operations.get(0));
    }

    @Override
    public TableResultInternal executeInternal(Operation operation) {
        Optional<? extends TableResult> tableResult = extendedExecutor.executeOperation(operation);
        if (tableResult.isPresent()) {
            TableResult result = tableResult.get();
            return TableResultImpl.builder()
                    .resultKind(result.getResultKind())
                    .schema(result.getResolvedSchema())
                    .data(CollUtil.newArrayList(result.collect()))
                    .build();
        }
        return super.executeInternal(operation);
    }

    @Override
    public SqlExplainResult explainStatementSet(List<JobStatement> statements, ExplainDetail... extraDetails) {
        SqlExplainResult.Builder resultBuilder = SqlExplainResult.Builder.newBuilder();
        List<Operation> operations = new ArrayList<>();
        for (JobStatement statement : statements) {
            if (statement.getSqlType().equals(SqlType.CTAS)) {
                resultBuilder
                        .sql(statement.getStatement())
                        .type(statement.getSqlType().getType())
                        .error("CTAS is not supported in Apache Flink 1.15.")
                        .parseTrue(false)
                        .explainTrue(false)
                        .explainTime(LocalDateTime.now());
                return resultBuilder.build();
            }
            if (statement.getSqlType().equals(SqlType.RTAS)) {
                resultBuilder
                        .sql(statement.getStatement())
                        .type(statement.getSqlType().getType())
                        .error("RTAS is not supported in Apache Flink 1.15.")
                        .parseTrue(false)
                        .explainTrue(false)
                        .explainTime(LocalDateTime.now());
                return resultBuilder.build();
            }
            try {
                List<Operation> itemOperations = getParser().parse(statement.getStatement());
                if (!itemOperations.isEmpty()) {
                    for (Operation operation : itemOperations) {
                        operations.add(operation);
                    }
                }
            } catch (Exception e) {
                String error = StrFormatter.format(
                        "Exception in explaining FlinkSQL:\n{}\n{}",
                        SqlUtil.addLineNumber(statement.getStatement()),
                        e.getMessage());
                resultBuilder
                        .sql(statement.getStatement())
                        .type(SqlType.INSERT.getType())
                        .error(error)
                        .parseTrue(false)
                        .explainTrue(false)
                        .explainTime(LocalDateTime.now());
                log.error(error);
                return resultBuilder.build();
            }
        }
        if (operations.isEmpty()) {
            throw new DinkyException("None of the job in the statement set.");
        }
        resultBuilder.parseTrue(true);
        resultBuilder.explain(getPlanner().explain(operations, extraDetails));
        return resultBuilder
                .explainTrue(true)
                .explainTime(LocalDateTime.now())
                .type(SqlType.INSERT.getType())
                .build();
    }

    @Override
    public TableResult executeStatementSet(List<JobStatement> statements) {
        statements.removeIf(statement -> statement.getSqlType().equals(SqlType.CTAS));
        statements.removeIf(statement -> statement.getSqlType().equals(SqlType.RTAS));
        statements.removeIf(statement -> !statement.getSqlType().isSinkyModify());
        List<ModifyOperation> modifyOperations = statements.stream()
                .map(statement -> getModifyOperationFromInsert(statement.getStatement()))
                .collect(Collectors.toList());
        return executeInternal(modifyOperations);
    }

    public ModifyOperation getModifyOperationFromInsert(String statement) {
        List<Operation> operations = getParser().parse(statement);
        if (operations.isEmpty()) {
            throw new TableException("None of the statement is parsed.");
        }
        if (operations.size() > 1) {
            throw new TableException("Only single statement is supported.");
        }
        Operation operation = operations.get(0);
        if (operation instanceof ModifyOperation) {
            return (ModifyOperation) operation;
        } else if (operation instanceof QueryOperation) {
            return new CollectModifyOperation((QueryOperation) operation);
        } else {
            log.info("Only insert statement or select is supported now. The statement is skipped: "
                    + operation.asSummaryString());
            return null;
        }
    }
}
