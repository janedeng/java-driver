/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.exceptions.SyntaxError;
import com.datastax.driver.core.exceptions.UnsupportedFeatureException;
import com.datastax.driver.core.policies.DelegatingLoadBalancingPolicy;
import com.datastax.driver.core.policies.RoundRobinPolicy;
import com.datastax.driver.core.utils.CassandraVersion;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import org.testng.annotations.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

import static com.datastax.driver.core.Assertions.*;
import static com.datastax.driver.core.TestUtils.nonQuietClusterCloseOptions;

/**
 * Simple test of the Sessions methods against a one node cluster.
 */
public class SessionTest extends CCMTestsSupport {

    private static final String TABLE1 = "test1";
    private static final String TABLE2 = "test2";
    private static final String TABLE3 = "test3";
    private static final String COUNTER_TABLE = "counters";

    @Override
    public void onTestContextInitialized() {
        execute(String.format(TestUtils.CREATE_TABLE_SIMPLE_FORMAT, TABLE1),
                String.format(TestUtils.CREATE_TABLE_SIMPLE_FORMAT, TABLE2),
                String.format(TestUtils.CREATE_TABLE_SIMPLE_FORMAT, TABLE3),
                String.format("CREATE TABLE %s (k text PRIMARY KEY, c counter)", COUNTER_TABLE));
    }

    @Test(groups = "short")
    public void should_execute_simple_statements() throws Exception {
        // Simple calls to all versions of the execute/executeAsync methods
        String key = "execute_test";
        ResultSet rs = session().execute(String.format(Locale.US, TestUtils.INSERT_FORMAT, TABLE1, key, "foo", 42, 24.03f));
        assertThat(rs.isExhausted()).isTrue();

        // execute
        checkExecuteResultSet(session().execute(String.format(TestUtils.SELECT_ALL_FORMAT, TABLE1)), key);
        checkExecuteResultSet(session().execute(new SimpleStatement(String.format(TestUtils.SELECT_ALL_FORMAT, TABLE1)).setConsistencyLevel(ConsistencyLevel.ONE)), key);

        // executeAsync
        checkExecuteResultSet(session().executeAsync(String.format(TestUtils.SELECT_ALL_FORMAT, TABLE1)).getUninterruptibly(), key);
        checkExecuteResultSet(session().executeAsync(new SimpleStatement(String.format(TestUtils.SELECT_ALL_FORMAT, TABLE1)).setConsistencyLevel(ConsistencyLevel.ONE)).getUninterruptibly(), key);
    }

    @Test(groups = "short")
    public void should_execute_prepared_statements() throws Exception {
        // Simple calls to all versions of the execute/executeAsync methods for prepared statements
        // Note: the goal is only to exercice the Session methods, PreparedStatementTest have better prepared statement tests.
        String key = "execute_prepared_test";
        ResultSet rs = session().execute(String.format(Locale.US, TestUtils.INSERT_FORMAT, TABLE2, key, "foo", 42, 24.03f));
        assertThat(rs.isExhausted()).isTrue();

        PreparedStatement p = session().prepare(String.format(TestUtils.SELECT_ALL_FORMAT + " WHERE k = ?", TABLE2));
        BoundStatement bs = p.bind(key);

        // executePrepared
        checkExecuteResultSet(session().execute(bs), key);
        checkExecuteResultSet(session().execute(bs.setConsistencyLevel(ConsistencyLevel.ONE)), key);

        // executePreparedAsync
        checkExecuteResultSet(session().executeAsync(bs).getUninterruptibly(), key);
        checkExecuteResultSet(session().executeAsync(bs.setConsistencyLevel(ConsistencyLevel.ONE)).getUninterruptibly(), key);
    }

    private static void checkExecuteResultSet(ResultSet rs, String key) {
        assertThat(rs.isExhausted()).isFalse();
        Row row = rs.one();
        assertThat(rs.isExhausted()).isTrue();
        assertThat(row.getString("k")).isEqualTo(key);
        assertThat(row.getString("t")).isEqualTo("foo");
        assertThat(row.getInt("i")).isEqualTo(42);
        assertThat(row.getFloat("f")).isEqualTo(24.03f, offset(0.1f));
    }

    @Test(groups = "short")
    public void should_execute_prepared_counter_statement() throws Exception {
        PreparedStatement p = session().prepare("UPDATE " + COUNTER_TABLE + " SET c = c + ? WHERE k = ?");

        session().execute(p.bind(1L, "row"));
        session().execute(p.bind(1L, "row"));

        ResultSet rs = session().execute("SELECT * FROM " + COUNTER_TABLE);
        List<Row> rows = rs.all();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLong("c")).isEqualTo(2L);
    }

    /**
     * Validates that a session can be established using snappy compression and executes some queries that inserts and
     * retrieves data using that session().
     *
     * @test_category connection:compression
     * @expected_result session established and queries made successfully using it.
     */
    @Test(groups = "short")
    public void should_function_with_snappy_compression() throws Exception {
        compressionTest(ProtocolOptions.Compression.SNAPPY);
    }

    /**
     * Validates that a session can be established using lz4 compression and executes some queries that inserts and
     * retrieves data using that session().
     *
     * @test_category connection:compression
     * @expected_result session established and queries made successfully using it.
     */
    @Test(groups = "short")
    @CassandraVersion(major = 2.0)
    public void should_function_with_lz4_compression() throws Exception {
        compressionTest(ProtocolOptions.Compression.LZ4);
    }

    public void compressionTest(ProtocolOptions.Compression compression) {
        cluster().getConfiguration().getProtocolOptions().setCompression(compression);
        try {
            Session compressedSession = cluster().connect(keyspace);

            // Simple calls to all versions of the execute/executeAsync methods
            String key = "execute_compressed_test_" + compression;
            ResultSet rs = compressedSession.execute(String.format(Locale.US, TestUtils.INSERT_FORMAT, TABLE3, key, "foo", 42, 24.03f));
            assertThat(rs.isExhausted()).isTrue();

            String SELECT_ALL = String.format(TestUtils.SELECT_ALL_FORMAT + " WHERE k = '%s'", TABLE3, key);

            // execute
            checkExecuteResultSet(compressedSession.execute(SELECT_ALL), key);
            checkExecuteResultSet(compressedSession.execute(new SimpleStatement(SELECT_ALL).setConsistencyLevel(ConsistencyLevel.ONE)), key);

            // executeAsync
            checkExecuteResultSet(compressedSession.executeAsync(SELECT_ALL).getUninterruptibly(), key);
            checkExecuteResultSet(compressedSession.executeAsync(new SimpleStatement(SELECT_ALL).setConsistencyLevel(ConsistencyLevel.ONE)).getUninterruptibly(), key);

        } finally {
            cluster().getConfiguration().getProtocolOptions().setCompression(ProtocolOptions.Compression.NONE);
        }
    }

    /**
     * Checks for deadlocks when a session shutdown races with the initialization of the cluster (JAVA-418).
     */
    @Test(groups = "short")
    public void should_close_properly_when_racing_with_cluster_init() throws InterruptedException {
        for (int i = 0; i < 500; i++) {

            // Use our own cluster and session (not the ones provided by the parent class) because we want an uninitialized cluster
            // (note the use of newSession below)
            final Cluster cluster = Cluster.builder()
                    .addContactPoints(getContactPoints())
                    .withPort(ccm().getBinaryPort())
                    .withNettyOptions(nonQuietClusterCloseOptions)
                    .build();
            try {
                final Session session = cluster.newSession();

                // Spawn two threads to simulate the race
                ExecutorService executor = Executors.newFixedThreadPool(2);
                final CountDownLatch startLatch = new CountDownLatch(1);

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startLatch.await();
                            cluster.init();
                        } catch (InterruptedException e) {
                            fail("unexpected interruption", e);
                        }
                    }
                });

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startLatch.await();
                            TimeUnit.MILLISECONDS.sleep(10);
                            session.close();
                        } catch (InterruptedException e) {
                            fail("unexpected interruption", e);
                        }
                    }
                });

                // Start the threads
                startLatch.countDown();

                executor.shutdown();
                boolean normalShutdown = executor.awaitTermination(5, TimeUnit.SECONDS);
                assertThat(normalShutdown).isTrue();

            } finally {
                // The deadlock occurred here before JAVA-418
                cluster.close();
            }
        }
    }

    /**
     * Ensures that if an attempt is made to create a {@link Session} via {@link Cluster#connect} with an invalid
     * keyspace that the returned exception is decorated with an indication to check that your keyspace name is valid
     * and includes the original {@link SyntaxError}.
     */
    @Test(groups = "short")
    public void should_give_explicit_error_message_when_keyspace_name_invalid() {
        try {
            cluster().connect("%!;");
            fail("Expected a SyntaxError");
        } catch (SyntaxError e) {
            assertThat(e.getMessage())
                    .contains("Error executing \"USE %!;\"")
                    .contains("Check that your keyspace name is valid");
        }
    }

    /**
     * Ensures that if an attempt is made to create a {@link Session} via {@link Cluster#connectAsync} with an invalid
     * keyspace that the returned exception is decorated with an indication to check that your keyspace name is valid
     * and includes the original {@link SyntaxError}.
     */
    @Test(groups = "short")
    public void should_give_explicit_error_message_when_keyspace_name_invalid_async() {
        ListenableFuture<Session> sessionFuture = cluster().connectAsync("");
        try {
            sessionFuture.get();
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(SyntaxError.class);
            assertThat(e.getCause().getMessage())
                    .contains("no viable alternative at input '<EOF>'")
                    .contains("Check that your keyspace name is valid");
        } catch (Exception e) {
            fail("Did not expect Exception", e);
        }
    }

    /**
     * Verifies that a call to session.executeAsync() does not throw an exception
     * when the request message cannot be created, but returns instead a ResultSetFuture
     * that is immediately failed.
     *
     * @jira_ticket JAVA-1020
     */
    @Test(groups = "short")
    public void should_return_failed_future_when_request_message_cannot_be_created() {
        Cluster cluster2 = register(
                createClusterBuilderNoDebouncing()
                        .addContactPoints(getContactPoints())
                        .withPort(ccm().getBinaryPort())
                        .withProtocolVersion(ProtocolVersion.V1)
                        .build());
        Session session2 = cluster2.connect();
        // fetch size not supported with protocol V1
        Statement stmt = new SimpleStatement("irrelevant").setFetchSize(10);
        ResultSetFuture future = session2.executeAsync(stmt);
        // the future should be already done, and failed
        assertThat(future.isDone()).isTrue();
        try {
            future.getUninterruptibly();
            fail("Expected DriverException");
        } catch (DriverException e) {
            // the original exception has been copy()ed
            assertThat(e.getCause()).isInstanceOf(UnsupportedFeatureException.class);
        }
    }

    /**
     * Verifies that a call to session.executeAsync() does not throw an exception
     * when the request message cannot be created, but returns instead a ResultSetFuture
     * that will fail immediately after the session is initialized.
     *
     * @jira_ticket JAVA-1020
     */
    @Test(groups = "short")
    public void should_return_failed_future_when_request_message_cannot_be_created_and_session_not_initialized() {
        Cluster cluster2 = register(
                createClusterBuilderNoDebouncing()
                        .addContactPoints(getContactPoints())
                        .withPort(ccm().getBinaryPort())
                        .withProtocolVersion(ProtocolVersion.V1)
                        .build());
        // do not initialize session yet
        Session session2 = cluster2.newSession();
        // fetch size not supported with protocol V1
        Statement stmt = new SimpleStatement("irrelevant").setFetchSize(10);
        // should trigger initAsync()
        ResultSetFuture future = session2.executeAsync(stmt);
        // the future can be in incomplete state until the session gets initialized
        try {
            future.getUninterruptibly();
            fail("Expected DriverException");
        } catch (DriverException e) {
            // the original exception has been copy()ed
            assertThat(e.getCause()).isInstanceOf(UnsupportedFeatureException.class);
        }
    }

    /**
     * Verifies that a call to session.executeAsync() does not throw an exception
     * when the request cannot be sent, but returns instead a ResultSetFuture
     * that is immediately failed.
     *
     * @jira_ticket JAVA-1020
     */
    @Test(groups = "short")
    public void should_return_failed_future_when_request_cannot_be_sent() {
        Cluster cluster2 = register(
                createClusterBuilderNoDebouncing()
                        .addContactPoints(getContactPoints())
                        .withPort(ccm().getBinaryPort())
                        .withLoadBalancingPolicy(new DelegatingLoadBalancingPolicy(new RoundRobinPolicy()) {
                            @Override
                            public Iterator<Host> newQueryPlan(String loggedKeyspace, Statement statement) {
                                return Iterators.emptyIterator();
                            }
                        })
                        .build());
        Session session2 = cluster2.connect();
        // will fail with NoHostAvailableException due to empty query plan
        ResultSetFuture future = session2.executeAsync("irrelevant");
        // the future should be already done, and failed
        assertThat(future.isDone()).isTrue();
        try {
            future.getUninterruptibly();
            fail("Expected NoHostAvailableException");
        } catch (NoHostAvailableException e) {
            // ok
        }
    }

    /**
     * Verifies that a call to session.executeAsync() does not throw an exception
     * when the request cannot be sent, but returns instead a ResultSetFuture
     * that will fail immediately after the session is initialized.
     *
     * @jira_ticket JAVA-1020
     */
    @Test(groups = "short")
    public void should_return_failed_future_when_request_cannot_be_sent_and_session_not_initialized() {
        Cluster cluster2 = register(
                createClusterBuilderNoDebouncing()
                        .addContactPoints(getContactPoints())
                        .withPort(ccm().getBinaryPort())
                        .withLoadBalancingPolicy(new DelegatingLoadBalancingPolicy(new RoundRobinPolicy()) {
                            @Override
                            public Iterator<Host> newQueryPlan(String loggedKeyspace, Statement statement) {
                                return Iterators.emptyIterator();
                            }
                        })
                        .build());
        // do not initialize session yet
        Session session2 = cluster2.newSession();
        // will fail with NoHostAvailableException due to empty query plan
        ResultSetFuture future = session2.executeAsync("irrelevant");
        // the future can be in incomplete state until the session gets initialized
        try {
            future.getUninterruptibly();
            fail("Expected NoHostAvailableException");
        } catch (NoHostAvailableException e) {
            // ok
        }
    }


    //---------------------------------------

    /**
     * Verifies that a call to session.prepareAsync() does not throw an exception
     * when the request cannot be sent, but returns instead a future
     * that is immediately failed.
     *
     * @jira_ticket JAVA-1020
     */
    @Test(groups = "short")
    public void should_return_failed_future_when_prepare_cannot_be_sent() {
        Cluster cluster2 = register(
                createClusterBuilderNoDebouncing()
                        .addContactPoints(getContactPoints())
                        .withPort(ccm().getBinaryPort())
                        .withLoadBalancingPolicy(new DelegatingLoadBalancingPolicy(new RoundRobinPolicy()) {
                            @Override
                            public Iterator<Host> newQueryPlan(String loggedKeyspace, Statement statement) {
                                return Iterators.emptyIterator();
                            }
                        })
                        .build());
        Session session2 = cluster2.connect();
        // will fail with NoHostAvailableException due to empty query plan
        ListenableFuture<PreparedStatement> future = session2.prepareAsync("irrelevant");
        // the future should be already done, and failed
        assertThat(future.isDone()).isTrue();
        try {
            Uninterruptibles.getUninterruptibly(future);
            fail("Expected ExecutionException");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(NoHostAvailableException.class);
        }
    }

    /**
     * Verifies that a call to session.prepareAsync() does not throw an exception
     * when the request cannot be sent, but returns instead a future
     * that will fail immediately after the session is initialized.
     *
     * @jira_ticket JAVA-1020
     */
    @Test(groups = "short")
    public void should_return_failed_future_when_prepare_cannot_be_sent_and_session_not_initialized() {
        Cluster cluster2 = register(
                createClusterBuilderNoDebouncing()
                        .addContactPoints(getContactPoints())
                        .withPort(ccm().getBinaryPort())
                        .withLoadBalancingPolicy(new DelegatingLoadBalancingPolicy(new RoundRobinPolicy()) {
                            @Override
                            public Iterator<Host> newQueryPlan(String loggedKeyspace, Statement statement) {
                                return Iterators.emptyIterator();
                            }
                        })
                        .build());
        // do not initialize session yet
        Session session2 = cluster2.newSession();
        // will fail with NoHostAvailableException due to empty query plan
        ListenableFuture<PreparedStatement> future = session2.prepareAsync("irrelevant");
        // the future can be in incomplete state until the session gets initialized
        try {
            Uninterruptibles.getUninterruptibly(future);
            fail("Expected ExecutionException");
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(NoHostAvailableException.class);
        }
    }

}
