/**
 * (C) Copyright IBM Corp. 2015, 2016
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
 *
 */

package com.ibm.spark.netezza

import java.util.Properties

import org.apache.spark.Partition
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources.{BaseRelation, Filter, PrunedFilteredScan}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Row, SQLContext}

/**
  *
  * Netezza relation that fetches only required columns and pushing filters to Netezza data base.
  * Generates schema based on the input table data types in the Netezza database.
  */
private[netezza] case class NetezzaRelation(
    url: String,
    table: String,
    parts: Array[Partition],
    properties: Properties = new Properties(),
    numPartitions: Int = 4)(@transient val sqlContext: SQLContext)
  extends BaseRelation
  with PrunedFilteredScan {

  override val schema: StructType = NetezzaSchema.getSparkSqlSchema(url, properties, table)

  /**
    * This method is introduced in 1.6 as optimization. But this package compiles from 1.5.
    */
  def unhandledFilters(filters: Array[Filter]): Array[Filter] = {
    filters.filter(NetezzaFilters.generateFilterExpr(_).isEmpty)
  }

  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {

    if (requiredColumns.isEmpty) {
      emptyRowRDD(filters)
    }
    else {
      // quote the column names to match Netezza table column names in case sensitive manner.
      val quotedColumns = requiredColumns.map(colName => s""""$colName"""")
      new NetezzaRDD(
        sqlContext.sparkContext,
        NetezzaJdbcUtils.getConnector(url, properties),
        NetezzaSchema.pruneSchema(schema, requiredColumns),
        table,
        quotedColumns,
        filters,
        parts,
        properties)
    }
  }

  /**
    * In case of select count, actual data is not needed to flow through the network to form a RDD,
    * a RDD with empty rows of the expected count will be returned to be counted.
    * @param filters the filters to apply to get correct number of rows
    * @return
    */
  private def emptyRowRDD(filters: Array[Filter]): RDD[Row] = {
    val numRows: Long = NetezzaJdbcUtils.getCountWithFilter(url, properties, table, filters)
    val emptyRow = Row.empty
    sqlContext.sparkContext.parallelize(1L to numRows, numPartitions).map(_ => emptyRow)
  }

  /**
    * The results of the toString() method of this class is shown in the spark sql plan outpit.
    * Overiding the toString() to avoid output does not become big, and also does not
    * include server credentials.
    */
  override def toString: String = {
    // credentials should not be included in the plan output, table information is sufficient.
    s"NetezzaRelation(${table})"
  }
}
