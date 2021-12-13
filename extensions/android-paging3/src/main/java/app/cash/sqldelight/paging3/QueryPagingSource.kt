/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.sqldelight.paging3

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates

internal abstract class QueryPagingSource<Key : Any, RowType : Any> :
  PagingSource<Key, RowType>(),
  Query.Listener {

  protected var currentQuery: Query<RowType>? by Delegates.observable(null) { _, old, new ->
    old?.removeListener(this)
    new?.addListener(this)
  }

  init {
    registerInvalidatedCallback {
      currentQuery?.removeListener(this)
      currentQuery = null
    }
  }

  final override fun queryResultsChanged() = invalidate()
}

/**
 * Create a [PagingSource] that pages through results according to queries generated by
 * [queryProvider]. Queries returned by [queryProvider] should expect to do SQL offset/limit
 * based paging. For that reason, [countQuery] is required to calculate pages and page offsets.
 *
 * An example query returned by [queryProvider] could look like:
 *
 * ```sql
 * SELECT value FROM numbers
 * LIMIT 10
 * OFFSET 100;
 * ```
 *
 * Queries will be executed on [context].
 */
@Suppress("FunctionName")
fun <RowType : Any> QueryPagingSource(
  countQuery: Query<Long>,
  context: CoroutineContext = Dispatchers.IO,
  queryProvider: (limit: Long, offset: Long) -> Query<RowType>,
): PagingSource<Long, RowType> = OffsetQueryPagingSource(
  queryProvider,
  countQuery,
  context,
)

/**
 * Create a [PagingSource] that pages through results according to queries generated by
 * [queryProvider]. Queries returned by [queryProvider] should expected to do keyset paging.
 * For that reason, queries should be arranged by an non-ambigious `ORDER BY` clause. [Key] must
 * be a unique clause that rows are ordered by. For performance reasons, an index should be present
 * on [Key].
 *
 * [pageBoundariesProvider] is a callback that produces a query containing [Key] items that specifies
 * where each page boundary exists within the full dataset. For example:
 *
 * The dataset `[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]` ordered ascending with a page size of 2 would produce
 * page boundaries `[0, 2, 4, 6, 8]`.
 *
 * The dataset `[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]` ordered ascending with a page size of 3 would produce
 * page boundaries `[0, 3, 6, 9]`.
 *
 * Callbacks made from [pageBoundariesProvider] may include an `anchor` key which, if present,
 * should appear in the resulting query.

 * Because page boundaries are computed ahead of time, [PagingConfig.initialLoadSize] should match
 * [PagingConfig.pageSize]. Failing to do so will result in unexpected page sizes, as
 * [pageBoundariesProvider] is called a single time during the first call to [PagingSource.load]
 * on this source.
 *
 * Generally, it's only feasible to produce page boundaries using SQLite window functions. An example
 * query to generate page boundaries like shown above would look like the following.
 *
 * ```sql
 * SELECT value
 * FROM (
 *   SELECT
 *     value,
 *     CASE
 *       WHEN ((row_number() OVER(ORDER BY value ASC) - 1) % :limit) = 0 THEN 1
 *       WHEN value = :anchor THEN 1
 *       ELSE 0
 *     END page_boundary
 *   FROM numbers
 *   ORDER BY value ASC
 * )
 * WHERE page_boundary = 1;
 * ```
 *
 * SQLite window queries became available as of version 3.25.0. For this reason, consuming
 * applications will likely need a minSdk of 30 set _or_ bundle a SQLite module separate from the OS
 * provided module.
 *
 * An example query returned by [queryProvider] could look like:
 *
 * ```sql
 * SELECT value FROM numbers
 * WHERE value >= :beginInclusive AND (value < :endExclusive OR :endExclusive IS NULL)
 * ORDER BY value ASC;
 * ```
 *
 * Queries will be executed on [context].
 *
 * This [PagingSource] _does not_ support jumping. If your use case requires jumping, use the
 * offset based [QueryPagingSource] function.
 */
@Suppress("FunctionName")
fun <Key : Any, RowType : Any> QueryPagingSource(
  transacter: Transacter,
  context: CoroutineContext = Dispatchers.IO,
  pageBoundariesProvider: (anchor: Key?, limit: Long) -> Query<Key>,
  queryProvider: (beginInclusive: Key, endExclusive: Key?) -> Query<RowType>,
): PagingSource<Key, RowType> = KeyedQueryPagingSource(
  queryProvider,
  pageBoundariesProvider,
  transacter,
  context,
)
