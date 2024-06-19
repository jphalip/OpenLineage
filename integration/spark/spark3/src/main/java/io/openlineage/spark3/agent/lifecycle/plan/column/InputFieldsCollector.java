/*
/* Copyright 2018-2024 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark3.agent.lifecycle.plan.column;

import com.google.cloud.spark.bigquery.BigQueryRelation;
import io.openlineage.client.utils.DatasetIdentifier;
import io.openlineage.client.utils.jdbc.JdbcDatasetUtils;
import io.openlineage.spark.agent.lifecycle.Rdds;
import io.openlineage.spark.agent.lifecycle.plan.column.ColumnLevelLineageBuilder;
import io.openlineage.spark.agent.lifecycle.plan.column.ColumnLevelLineageContext;
import io.openlineage.spark.agent.util.BigQueryUtils;
import io.openlineage.spark.agent.util.ExtensionPlanUtils;
import io.openlineage.spark.agent.util.JdbcSparkUtils;
import io.openlineage.spark.agent.util.PathUtils;
import io.openlineage.spark.agent.util.PlanUtils;
import io.openlineage.spark.agent.util.ScalaConversionUtils;
import io.openlineage.spark.extension.scala.v1.ColumnLevelLineageNode;
import io.openlineage.spark.extension.scala.v1.DatasetFieldLineage;
import io.openlineage.spark.extension.scala.v1.InputDatasetFieldFromDelegate;
import io.openlineage.spark.extension.scala.v1.InputDatasetFieldWithIdentifier;
import io.openlineage.spark3.agent.utils.PlanUtils3;
import io.openlineage.sql.SqlMeta;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.Path;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.catalyst.catalog.CatalogTable;
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.ExprId;
import org.apache.spark.sql.catalyst.plans.logical.LeafNode;
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.OneRowRelation;
import org.apache.spark.sql.catalyst.plans.logical.UnaryNode;
import org.apache.spark.sql.execution.ExternalRDD;
import org.apache.spark.sql.execution.LogicalRDD;
import org.apache.spark.sql.execution.columnar.InMemoryRelation;
import org.apache.spark.sql.execution.datasources.HadoopFsRelation;
import org.apache.spark.sql.execution.datasources.LogicalRelation;
import org.apache.spark.sql.execution.datasources.jdbc.JDBCRelation;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2ScanRelation;

/** Traverses LogicalPlan and collect input fields with the corresponding ExprId. */
@Slf4j
public class InputFieldsCollector {

  public static void collect(ColumnLevelLineageContext context, LogicalPlan plan) {
    discoverInputsFromNode(context, plan);
    CustomCollectorsUtils.collectInputs(context, plan);

    if (plan instanceof ColumnLevelLineageNode) {
      extensionColumnLineage(context, (ColumnLevelLineageNode) plan);
    }

    // hacky way to replace `plan instanceof UnaryNode` which fails for Spark 3.2.1
    // because of java.lang.IncompatibleClassChangeError: UnaryNode, but class was expected
    // probably related to single code base for different Spark versions
    if ((plan.getClass()).isAssignableFrom(UnaryNode.class)) {
      collect(context, ((UnaryNode) plan).child());
    } else if (plan.children() != null) {
      ScalaConversionUtils.<LogicalPlan>fromSeq(plan.children()).stream()
          .forEach(child -> collect(context, child));
    }
  }

  private static void discoverInputsFromNode(ColumnLevelLineageContext context, LogicalPlan node) {
    List<DatasetIdentifier> datasetIdentifiers = extractDatasetIdentifier(context, node);
    if (isJDBCNode(node)) {
      JdbcColumnLineageCollector.extractExternalInputs(context, node, datasetIdentifiers);
    } else {
      extractInternalInputs(node, context.getBuilder(), datasetIdentifiers);
    }
  }

  private static void extensionColumnLineage(
      ColumnLevelLineageContext context, ColumnLevelLineageNode node) {
    List<DatasetFieldLineage> inputs =
        ScalaConversionUtils.<DatasetFieldLineage>fromSeq(
            node.columnLevelLineageInputs(
                    ExtensionPlanUtils.context(context.getEvent(), context.getOlContext()))
                .toSeq());

    inputs.stream()
        .filter(i -> i instanceof InputDatasetFieldWithIdentifier)
        .map(i -> (InputDatasetFieldWithIdentifier) i)
        .forEach(
            i ->
                context
                    .getBuilder()
                    .addInput(
                        ExprId.apply(i.exprId().exprId()),
                        new DatasetIdentifier(
                            i.datasetIdentifier().getNamespace(), i.datasetIdentifier().getName()),
                        i.field()));

    inputs.stream()
        .filter(i -> i instanceof InputDatasetFieldFromDelegate)
        .map(i -> (InputDatasetFieldFromDelegate) i)
        .filter(i -> i.delegate() instanceof LogicalPlan)
        .forEach(i -> discoverInputsFromNode(context, (LogicalPlan) i.delegate()));
  }

  private static boolean isJDBCNode(LogicalPlan node) {
    return node instanceof LogicalRelation
        && ((LogicalRelation) node).relation() instanceof JDBCRelation;
  }

  private static void extractInternalInputs(
      LogicalPlan node,
      ColumnLevelLineageBuilder builder,
      List<DatasetIdentifier> datasetIdentifiers) {

    datasetIdentifiers.stream()
        .forEach(
            di -> {
              ScalaConversionUtils.fromSeq(node.output()).stream()
                  .filter(attr -> attr instanceof AttributeReference)
                  .map(attr -> (AttributeReference) attr)
                  .collect(Collectors.toList())
                  .forEach(attr -> builder.addInput(attr.exprId(), di, attr.name()));
            });
  }

  private static List<DatasetIdentifier> extractDatasetIdentifier(
      ColumnLevelLineageContext context, LogicalPlan node) {
    if (node instanceof DataSourceV2Relation) {
      return extractDatasetIdentifier(context, (DataSourceV2Relation) node);
    } else if (node instanceof DataSourceV2ScanRelation) {
      return extractDatasetIdentifier(context, ((DataSourceV2ScanRelation) node).relation());
    } else if (node instanceof HiveTableRelation) {
      return extractDatasetIdentifier(((HiveTableRelation) node).tableMeta());
    } else if (node instanceof LogicalRelation
        && ((LogicalRelation) node).catalogTable().isDefined()) {
      return extractDatasetIdentifier(((LogicalRelation) node).catalogTable().get());
    } else if (node instanceof LogicalRelation
        && (((LogicalRelation) node).relation() instanceof HadoopFsRelation)) {
      HadoopFsRelation relation = (HadoopFsRelation) ((LogicalRelation) node).relation();
      return extractDatasetIdentifier(relation);
    } else if (node instanceof LogicalRelation
        && BigQueryUtils.hasBigQueryClasses()
        && ((LogicalRelation) node).relation() instanceof BigQueryRelation) {
      BigQueryRelation relation = (BigQueryRelation) ((LogicalRelation) node).relation();
      return BigQueryUtils.extractDatasetIdentifier(relation);
    } else if (node instanceof LogicalRelation
        && ((LogicalRelation) node).relation() instanceof JDBCRelation) {
      JDBCRelation relation = (JDBCRelation) ((LogicalRelation) node).relation();
      return extractDatasetIdentifier(context, relation);
    } else if (node instanceof LogicalRDD) {
      return extractDatasetIdentifier((LogicalRDD) node);
    } else if (node instanceof InMemoryRelation) {
      // implemented in
      // io.openlineage.spark3.agent.lifecycle.plan.column.ColumnLevelLineageUtils.collectInputsAndExpressionDependencies
      // requires merging multiple LogicalPlans
    } else if (node instanceof OneRowRelation
        || node instanceof LocalRelation
        || node instanceof ExternalRDD) {
      // skip without warning
    } else if (node instanceof LeafNode) {
      log.warn("Could not extract dataset identifier from {}", node.getClass().getCanonicalName());
    }
    return Collections.emptyList();
  }

  private static List<DatasetIdentifier> extractDatasetIdentifier(
      ColumnLevelLineageContext context, JDBCRelation relation) {
    Optional<SqlMeta> sqlMeta = JdbcSparkUtils.extractQueryFromSpark(relation);
    String jdbcUrl = relation.jdbcOptions().url();
    Properties jdbcProperties = relation.jdbcOptions().asConnectionProperties();
    return sqlMeta
        .map(
            meta ->
                meta.inTables().stream()
                    .map(
                        table ->
                            context
                                .getNamespaceResolver()
                                .resolve(
                                    JdbcDatasetUtils.getDatasetIdentifier(
                                        jdbcUrl, table.qualifiedName(), jdbcProperties)))
                    .collect(Collectors.toList()))
        .orElse(Collections.emptyList());
  }

  private static List<DatasetIdentifier> extractDatasetIdentifier(LogicalRDD logicalRDD) {
    List<RDD<?>> fileLikeRdds = Rdds.findFileLikeRdds(logicalRDD.rdd());
    return PlanUtils.findRDDPaths(fileLikeRdds).stream()
        .map(path -> PathUtils.fromURI(path.toUri()))
        .collect(Collectors.toList());
  }

  private static List<DatasetIdentifier> extractDatasetIdentifier(
      ColumnLevelLineageContext context, DataSourceV2Relation relation) {
    return PlanUtils3.getDatasetIdentifier(context.getOlContext(), relation)
        .map(Collections::singletonList)
        .orElse(Collections.emptyList());
  }

  private static List<DatasetIdentifier> extractDatasetIdentifier(CatalogTable catalogTable) {
    URI location = catalogTable.location();
    if (location == null) {
      return Collections.emptyList();
    } else {
      return Collections.singletonList(
          new DatasetIdentifier(
              catalogTable.location().getPath(), PlanUtils.namespaceUri(catalogTable.location())));
    }
  }

  /* Similar to the InsertIntoHadoopFsRelationVisitor and LogicalRelationDatasetBuilder
   * We need to handle a HadoopFsRelation by extracting that paths it traverses
   * to identify the datasets being used.
   */
  private static List<DatasetIdentifier> extractDatasetIdentifier(HadoopFsRelation relation) {
    List<DatasetIdentifier> inputDatasets = new ArrayList<DatasetIdentifier>();
    List<Path> paths =
        ScalaConversionUtils.fromSeq(relation.location().rootPaths()).stream()
            .collect(Collectors.toList());

    for (Path p : paths) {
      String namespace = PlanUtils.namespaceUri(p.toUri());
      inputDatasets.add(new DatasetIdentifier(p.toUri().getPath(), namespace));
    }

    return inputDatasets;
  }
}
