/*
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

package org.apache.flink.table.store.file.operation;

import org.apache.flink.table.store.file.casting.CastExecutor;
import org.apache.flink.table.store.file.manifest.ManifestEntry;
import org.apache.flink.table.store.file.manifest.ManifestFile;
import org.apache.flink.table.store.file.manifest.ManifestList;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.schema.KeyValueFieldsExtractor;
import org.apache.flink.table.store.file.schema.SchemaEvolutionUtil;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.file.stats.FieldStatsArraySerializer;
import org.apache.flink.table.store.file.utils.SnapshotManager;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.LogicalTypeConversion;
import org.apache.flink.table.types.logical.RowType;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.flink.table.store.file.predicate.PredicateBuilder.and;
import static org.apache.flink.table.store.file.predicate.PredicateBuilder.pickTransformFieldMapping;
import static org.apache.flink.table.store.file.predicate.PredicateBuilder.splitAnd;

/** {@link FileStoreScan} for {@link org.apache.flink.table.store.file.KeyValueFileStore}. */
public class KeyValueFileStoreScan extends AbstractFileStoreScan {

    private final ConcurrentMap<Long, FieldStatsArraySerializer> schemaKeyStatsConverters;
    private final KeyValueFieldsExtractor keyValueFieldsExtractor;
    private final RowType keyType;

    private Predicate keyFilter;

    public KeyValueFileStoreScan(
            RowType partitionType,
            RowType bucketKeyType,
            RowType keyType,
            SnapshotManager snapshotManager,
            SchemaManager schemaManager,
            long schemaId,
            KeyValueFieldsExtractor keyValueFieldsExtractor,
            ManifestFile.Factory manifestFileFactory,
            ManifestList.Factory manifestListFactory,
            int numOfBuckets,
            boolean checkNumOfBuckets) {
        super(
                partitionType,
                bucketKeyType,
                snapshotManager,
                schemaManager,
                schemaId,
                manifestFileFactory,
                manifestListFactory,
                numOfBuckets,
                checkNumOfBuckets);
        this.keyValueFieldsExtractor = keyValueFieldsExtractor;
        this.schemaKeyStatsConverters = new ConcurrentHashMap<>();
        this.keyType = keyType;
    }

    public KeyValueFileStoreScan withKeyFilter(Predicate predicate) {
        this.keyFilter = predicate;

        List<Predicate> bucketFilters =
                pickTransformFieldMapping(
                        splitAnd(predicate),
                        keyType.getFieldNames(),
                        bucketKeyType.getFieldNames());
        if (bucketFilters.size() > 0) {
            withBucketKeyFilter(and(bucketFilters));
        }
        return this;
    }

    /** Note: Keep this thread-safe. */
    @Override
    protected boolean filterByStats(ManifestEntry entry) {
        return keyFilter == null
                || keyFilter.test(
                        entry.file().rowCount(),
                        entry.file()
                                .keyStats()
                                .fields(
                                        getFieldStatsArraySerializer(entry.file().schemaId()),
                                        entry.file().rowCount()));
    }

    /** Note: Keep this thread-safe. */
    private FieldStatsArraySerializer getFieldStatsArraySerializer(long id) {
        return schemaKeyStatsConverters.computeIfAbsent(
                id,
                key -> {
                    final TableSchema tableSchema = scanTableSchema();
                    final TableSchema schema = scanTableSchema(key);
                    final List<DataField> tableKeyFields =
                            keyValueFieldsExtractor.keyFields(tableSchema);
                    final List<DataField> keyFields = keyValueFieldsExtractor.keyFields(schema);
                    final int[] indexMapping =
                            tableSchema.id() == key
                                    ? null
                                    : SchemaEvolutionUtil.createIndexMapping(
                                            tableKeyFields, keyFields);
                    final CastExecutor<?, ?>[] converterMapping =
                            tableSchema.id() == key
                                    ? null
                                    : SchemaEvolutionUtil.createConvertMapping(
                                            tableKeyFields, keyFields, indexMapping);
                    return new FieldStatsArraySerializer(
                            LogicalTypeConversion.toRowType(keyFields),
                            indexMapping,
                            (CastExecutor<Object, Object>[]) converterMapping);
                });
    }
}