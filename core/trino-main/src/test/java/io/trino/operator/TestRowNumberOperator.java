/*
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
package io.trino.operator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import io.trino.RowPagesBuilder;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeOperators;
import io.trino.sql.gen.JoinCompiler;
import io.trino.sql.planner.plan.PlanNodeId;
import io.trino.testing.MaterializedResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.testing.Assertions.assertEqualsIgnoreOrder;
import static io.airlift.testing.Assertions.assertGreaterThanOrEqual;
import static io.trino.RowPagesBuilder.rowPagesBuilder;
import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.operator.GroupByHashYieldAssertion.createPagesWithDistinctHashKeys;
import static io.trino.operator.GroupByHashYieldAssertion.finishOperatorWithYieldingGroupByHash;
import static io.trino.operator.OperatorAssertion.toMaterializedResult;
import static io.trino.operator.OperatorAssertion.toPages;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.TestingTaskContext.createTaskContext;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@TestInstance(PER_CLASS)
public class TestRowNumberOperator
{
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private final JoinCompiler joinCompiler = new JoinCompiler(new TypeOperators());

    @BeforeAll
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed(getClass().getSimpleName() + "-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed(getClass().getSimpleName() + "-scheduledExecutor-%s"));
    }

    @AfterAll
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    private DriverContext getDriverContext()
    {
        return createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
    }

    @Test
    public void testRowNumberUnpartitioned()
    {
        DriverContext driverContext = getDriverContext();
        List<Page> input = rowPagesBuilder(BIGINT, DOUBLE)
                .row(1L, 0.3)
                .row(2L, 0.2)
                .row(3L, 0.1)
                .row(3L, 0.19)
                .pageBreak()
                .row(1L, 0.4)
                .pageBreak()
                .row(1L, 0.5)
                .row(1L, 0.6)
                .row(2L, 0.7)
                .row(2L, 0.8)
                .row(2L, 0.9)
                .build();

        RowNumberOperator.RowNumberOperatorFactory operatorFactory = new RowNumberOperator.RowNumberOperatorFactory(
                0,
                new PlanNodeId("test"),
                ImmutableList.of(BIGINT, DOUBLE),
                Ints.asList(1, 0),
                Ints.asList(),
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty(),
                10,
                joinCompiler);

        MaterializedResult expectedResult = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                .row(0.3, 1L)
                .row(0.4, 1L)
                .row(0.5, 1L)
                .row(0.6, 1L)
                .row(0.2, 2L)
                .row(0.7, 2L)
                .row(0.8, 2L)
                .row(0.9, 2L)
                .row(0.1, 3L)
                .row(0.19, 3L)
                .build();

        List<Page> pages = toPages(operatorFactory, driverContext, input);
        Block rowNumberColumn = getRowNumberColumn(pages);
        assertEquals(rowNumberColumn.getPositionCount(), 10);

        pages = stripRowNumberColumn(pages);
        MaterializedResult actual = toMaterializedResult(driverContext.getSession(), ImmutableList.of(DOUBLE, BIGINT), pages);
        assertEqualsIgnoreOrder(actual.getMaterializedRows(), expectedResult.getMaterializedRows());
    }

    @Test
    public void testMemoryReservationYield()
    {
        for (Type type : Arrays.asList(VARCHAR, BIGINT)) {
            List<Page> input = createPagesWithDistinctHashKeys(type, 6_000, 600);

            OperatorFactory operatorFactory = new RowNumberOperator.RowNumberOperatorFactory(
                    0,
                    new PlanNodeId("test"),
                    ImmutableList.of(type),
                    ImmutableList.of(0),
                    ImmutableList.of(0),
                    ImmutableList.of(type),
                    Optional.empty(),
                    Optional.of(1),
                    1,
                    joinCompiler);

            // get result with yield; pick a relatively small buffer for partitionRowCount's memory usage
            GroupByHashYieldAssertion.GroupByHashYieldResult result = finishOperatorWithYieldingGroupByHash(input, type, operatorFactory, operator -> ((RowNumberOperator) operator).getCapacity(), 280_000);
            assertGreaterThanOrEqual(result.getYieldCount(), 5);
            assertGreaterThanOrEqual(result.getMaxReservedBytes(), 20L << 20);

            int count = 0;
            for (Page page : result.getOutput()) {
                assertEquals(page.getChannelCount(), 3);
                for (int i = 0; i < page.getPositionCount(); i++) {
                    assertEquals(page.getBlock(2).getLong(i, 0), 1);
                    count++;
                }
            }
            assertEquals(count, 6_000 * 600);
        }
    }

    @Test
    public void testRowNumberPartitioned()
    {
        for (boolean hashEnabled : Arrays.asList(true, false)) {
            DriverContext driverContext = getDriverContext();
            RowPagesBuilder rowPagesBuilder = rowPagesBuilder(hashEnabled, Ints.asList(0), BIGINT, DOUBLE);
            List<Page> input = rowPagesBuilder
                    .row(1L, 0.3)
                    .row(2L, 0.2)
                    .row(3L, 0.1)
                    .row(3L, 0.19)
                    .pageBreak()
                    .row(1L, 0.4)
                    .pageBreak()
                    .row(1L, 0.5)
                    .row(1L, 0.6)
                    .row(2L, 0.7)
                    .row(2L, 0.8)
                    .row(2L, 0.9)
                    .build();

            RowNumberOperator.RowNumberOperatorFactory operatorFactory = new RowNumberOperator.RowNumberOperatorFactory(
                    0,
                    new PlanNodeId("test"),
                    ImmutableList.of(BIGINT, DOUBLE),
                    Ints.asList(1, 0),
                    Ints.asList(0),
                    ImmutableList.of(BIGINT),
                    Optional.of(10),
                    rowPagesBuilder.getHashChannel(),
                    10,
                    joinCompiler);

            MaterializedResult expectedPartition1 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                    .row(0.3, 1L)
                    .row(0.4, 1L)
                    .row(0.5, 1L)
                    .row(0.6, 1L)
                    .build();

            MaterializedResult expectedPartition2 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                    .row(0.2, 2L)
                    .row(0.7, 2L)
                    .row(0.8, 2L)
                    .row(0.9, 2L)
                    .build();

            MaterializedResult expectedPartition3 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                    .row(0.1, 3L)
                    .row(0.19, 3L)
                    .build();

            List<Page> pages = toPages(operatorFactory, driverContext, input);
            Block rowNumberColumn = getRowNumberColumn(pages);
            assertEquals(rowNumberColumn.getPositionCount(), 10);

            pages = stripRowNumberColumn(pages);
            MaterializedResult actual = toMaterializedResult(driverContext.getSession(), ImmutableList.of(DOUBLE, BIGINT), pages);
            ImmutableSet<?> actualSet = ImmutableSet.copyOf(actual.getMaterializedRows());
            ImmutableSet<?> expectedPartition1Set = ImmutableSet.copyOf(expectedPartition1.getMaterializedRows());
            ImmutableSet<?> expectedPartition2Set = ImmutableSet.copyOf(expectedPartition2.getMaterializedRows());
            ImmutableSet<?> expectedPartition3Set = ImmutableSet.copyOf(expectedPartition3.getMaterializedRows());
            assertEquals(Sets.intersection(expectedPartition1Set, actualSet).size(), 4);
            assertEquals(Sets.intersection(expectedPartition2Set, actualSet).size(), 4);
            assertEquals(Sets.intersection(expectedPartition3Set, actualSet).size(), 2);
        }
    }

    @Test
    public void testRowNumberPartitionedLimit()
    {
        for (boolean hashEnabled : Arrays.asList(true, false)) {
            DriverContext driverContext = getDriverContext();
            RowPagesBuilder rowPagesBuilder = rowPagesBuilder(hashEnabled, Ints.asList(0), BIGINT, DOUBLE);
            List<Page> input = rowPagesBuilder
                    .row(1L, 0.3)
                    .row(2L, 0.2)
                    .row(3L, 0.1)
                    .row(3L, 0.19)
                    .pageBreak()
                    .row(1L, 0.4)
                    .pageBreak()
                    .row(1L, 0.5)
                    .row(1L, 0.6)
                    .row(2L, 0.7)
                    .row(2L, 0.8)
                    .row(2L, 0.9)
                    .build();

            RowNumberOperator.RowNumberOperatorFactory operatorFactory = new RowNumberOperator.RowNumberOperatorFactory(
                    0,
                    new PlanNodeId("test"),
                    ImmutableList.of(BIGINT, DOUBLE),
                    Ints.asList(1, 0),
                    Ints.asList(0),
                    ImmutableList.of(BIGINT),
                    Optional.of(3),
                    Optional.empty(),
                    10,
                    joinCompiler);

            MaterializedResult expectedPartition1 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                    .row(0.3, 1L)
                    .row(0.4, 1L)
                    .row(0.5, 1L)
                    .row(0.6, 1L)
                    .build();

            MaterializedResult expectedPartition2 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                    .row(0.2, 2L)
                    .row(0.7, 2L)
                    .row(0.8, 2L)
                    .row(0.9, 2L)
                    .build();

            MaterializedResult expectedPartition3 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                    .row(0.1, 3L)
                    .row(0.19, 3L)
                    .build();

            List<Page> pages = toPages(operatorFactory, driverContext, input);
            Block rowNumberColumn = getRowNumberColumn(pages);
            assertEquals(rowNumberColumn.getPositionCount(), 8);
            // Check that all row numbers generated are <= 3
            for (int i = 0; i < rowNumberColumn.getPositionCount(); i++) {
                assertTrue(rowNumberColumn.getLong(i, 0) <= 3);
            }

            pages = stripRowNumberColumn(pages);
            MaterializedResult actual = toMaterializedResult(driverContext.getSession(), ImmutableList.of(DOUBLE, BIGINT), pages);
            ImmutableSet<?> actualSet = ImmutableSet.copyOf(actual.getMaterializedRows());
            ImmutableSet<?> expectedPartition1Set = ImmutableSet.copyOf(expectedPartition1.getMaterializedRows());
            ImmutableSet<?> expectedPartition2Set = ImmutableSet.copyOf(expectedPartition2.getMaterializedRows());
            ImmutableSet<?> expectedPartition3Set = ImmutableSet.copyOf(expectedPartition3.getMaterializedRows());
            assertEquals(Sets.intersection(expectedPartition1Set, actualSet).size(), 3);
            assertEquals(Sets.intersection(expectedPartition2Set, actualSet).size(), 3);
            assertEquals(Sets.intersection(expectedPartition3Set, actualSet).size(), 2);
        }
    }

    @Test
    public void testRowNumberUnpartitionedLimit()
    {
        DriverContext driverContext = getDriverContext();
        List<Page> input = rowPagesBuilder(BIGINT, DOUBLE)
                .row(1L, 0.3)
                .row(2L, 0.2)
                .row(3L, 0.1)
                .row(3L, 0.19)
                .pageBreak()
                .row(1L, 0.4)
                .pageBreak()
                .row(1L, 0.5)
                .row(1L, 0.6)
                .row(2L, 0.7)
                .row(2L, 0.8)
                .row(2L, 0.9)
                .build();

        RowNumberOperator.RowNumberOperatorFactory operatorFactory = new RowNumberOperator.RowNumberOperatorFactory(
                0,
                new PlanNodeId("test"),
                ImmutableList.of(BIGINT, DOUBLE),
                Ints.asList(1, 0),
                Ints.asList(),
                ImmutableList.of(),
                Optional.of(3),
                Optional.empty(),
                10,
                joinCompiler);

        MaterializedResult expectedRows = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT, BIGINT)
                .row(0.3, 1L)
                .row(0.2, 2L)
                .row(0.1, 3L)
                .row(0.19, 3L)
                .row(0.4, 1L)
                .row(0.5, 1L)
                .row(0.6, 1L)
                .row(0.7, 2L)
                .row(0.8, 2L)
                .row(0.9, 2L)
                .build();

        List<Page> pages = toPages(operatorFactory, driverContext, input);
        Block rowNumberColumn = getRowNumberColumn(pages);
        assertEquals(rowNumberColumn.getPositionCount(), 3);

        pages = stripRowNumberColumn(pages);
        MaterializedResult actual = toMaterializedResult(driverContext.getSession(), ImmutableList.of(DOUBLE, BIGINT), pages);
        assertEquals(actual.getMaterializedRows().size(), 3);
        ImmutableSet<?> actualSet = ImmutableSet.copyOf(actual.getMaterializedRows());
        ImmutableSet<?> expectedRowsSet = ImmutableSet.copyOf(expectedRows.getMaterializedRows());
        assertEquals(Sets.intersection(expectedRowsSet, actualSet).size(), 3);
    }

    private static Block getRowNumberColumn(List<Page> pages)
    {
        BlockBuilder builder = BIGINT.createBlockBuilder(null, pages.size() * 100);
        for (Page page : pages) {
            int rowNumberChannel = page.getChannelCount() - 1;
            for (int i = 0; i < page.getPositionCount(); i++) {
                BIGINT.writeLong(builder, page.getBlock(rowNumberChannel).getLong(i, 0));
            }
        }
        return builder.build();
    }

    private static List<Page> stripRowNumberColumn(List<Page> input)
    {
        return input.stream()
                .map(page -> {
                    Block[] blocks = new Block[page.getChannelCount() - 1];
                    for (int i = 0; i < page.getChannelCount() - 1; i++) {
                        blocks[i] = page.getBlock(i);
                    }
                    return new Page(page.getPositionCount(), blocks);
                })
                .collect(toImmutableList());
    }
}
