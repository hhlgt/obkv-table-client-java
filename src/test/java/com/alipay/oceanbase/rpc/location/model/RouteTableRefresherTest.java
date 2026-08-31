/*-
 * #%L
 * com.oceanbase:obkv-table-client
 * %%
 * Copyright (C) 2021 - 2026 OceanBase
 * %%
 * OBKV Table Client Framework is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 * #L%
 */
package com.alipay.oceanbase.rpc.location.model;

import com.alipay.oceanbase.rpc.ObTableClient;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteTableRefresherTest {

    private RouteTableRefresher refresherA;
    private RouteTableRefresher refresherB;

    @After
    public void tearDown() {
        if (refresherA != null) {
            refresherA.close();
        }
        if (refresherB != null) {
            refresherB.close();
        }
    }

    @Test
    public void suspectStateIsIsolatedPerClient() {
        ObServerAddr addr = server("127.0.0.1");
        refresherA = refresherFor(new RecordingTableRoute());
        refresherB = refresherFor(new RecordingTableRoute());
        refresherA.refreshActiveServers(Collections.singleton(addr));
        refresherB.refreshActiveServers(Collections.singleton(addr));

        refresherA.addIntoSuspectIPs(addr);

        assertTrue(refresherA.containsSuspectServer(addr));
        assertFalse(refresherB.containsSuspectServer(addr));
        assertEquals(1, refresherA.suspectServerCount());
        assertEquals(0, refresherB.suspectServerCount());
    }

    @Test
    public void rosterRetirementClearsStateAndRejectsStaleReports() {
        ObServerAddr addr = server("127.0.0.2");
        refresherA = refresherFor(new RecordingTableRoute());
        refresherA.refreshActiveServers(Collections.singleton(addr));
        refresherA.addIntoSuspectIPs(addr);
        assertEquals(1, refresherA.lastAccessTimestampCount());

        refresherA.refreshActiveServers(Collections.emptyList());
        refresherA.addIntoSuspectIPs(addr);

        assertFalse(refresherA.containsSuspectServer(addr));
        assertEquals(0, refresherA.lastAccessTimestampCount());
    }

    @Test
    public void staleFailureAfterRosterRemovalIsIdempotent() throws Exception {
        ObServerAddr addr = server("127.0.0.3");
        RecordingTableRoute route = new RecordingTableRoute();
        refresherA = refresherFor(route);
        refresherA.refreshActiveServers(Collections.singleton(addr));
        refresherA.addIntoSuspectIPs(addr);
        refresherA.refreshActiveServers(Collections.emptyList());

        invokePrivate(refresherA, "calcFailureOrClearCache", addr);
        invokePrivate(refresherA, "removeFromSuspectIPs", addr);
        invokePrivate(refresherA, "removeFromSuspectIPs", addr);

        assertEquals(0, route.removeCount.get());
        assertEquals(0, refresherA.suspectServerCount());
    }

    @Test
    public void failureLimitEvictsServerOnlyOnce() throws Exception {
        ObServerAddr addr = server("127.0.0.4");
        RecordingTableRoute route = new RecordingTableRoute();
        refresherA = refresherFor(route);
        refresherA.refreshActiveServers(Collections.singleton(addr));
        refresherA.addIntoSuspectIPs(addr);

        invokePrivate(refresherA, "calcFailureOrClearCache", addr);
        invokePrivate(refresherA, "calcFailureOrClearCache", addr);
        invokePrivate(refresherA, "calcFailureOrClearCache", addr);
        invokePrivate(refresherA, "calcFailureOrClearCache", addr);

        assertEquals(1, route.removeCount.get());
        assertEquals(0, refresherA.suspectServerCount());
    }

    @Test
    public void closeClearsInstanceState() {
        ObServerAddr addr = server("127.0.0.5");
        refresherA = refresherFor(new RecordingTableRoute());
        refresherA.refreshActiveServers(Collections.singleton(addr));
        refresherA.addIntoSuspectIPs(addr);

        refresherA.close();

        assertEquals(0, refresherA.suspectServerCount());
        assertEquals(0, refresherA.lastAccessTimestampCount());
    }

    @Test
    public void closingOneClientDoesNotClearAnotherClientState() {
        ObServerAddr addr = server("127.0.0.6");
        refresherA = refresherFor(new RecordingTableRoute());
        refresherB = refresherFor(new RecordingTableRoute());
        refresherA.refreshActiveServers(Collections.singleton(addr));
        refresherB.refreshActiveServers(Collections.singleton(addr));
        refresherA.addIntoSuspectIPs(addr);
        refresherB.addIntoSuspectIPs(addr);

        refresherA.close();

        assertEquals(0, refresherA.suspectServerCount());
        assertEquals(1, refresherB.suspectServerCount());
    }

    private RouteTableRefresher refresherFor(TableRoute route) {
        return new RouteTableRefresher(new TestObTableClient(route), new ObUserAuth("root@sys",
            "unused"));
    }

    private ObServerAddr server(String ip) {
        return new ObServerAddr(ip, 2881, 2882);
    }

    private void invokePrivate(RouteTableRefresher refresher, String methodName, ObServerAddr addr)
                                                                                                   throws Exception {
        Method method = RouteTableRefresher.class.getDeclaredMethod(methodName, ObServerAddr.class);
        method.setAccessible(true);
        method.invoke(refresher, addr);
    }

    private static final class TestObTableClient extends ObTableClient {
        private final TableRoute route;

        private TestObTableClient(TableRoute route) {
            this.route = route;
        }

        @Override
        public TableRoute getTableRoute() {
            return route;
        }
    }

    private static final class RecordingTableRoute extends TableRoute {
        private final AtomicInteger removeCount = new AtomicInteger();

        private RecordingTableRoute() {
            super(null, null);
        }

        @Override
        public void removeObServer(ObServerAddr addr) {
            removeCount.incrementAndGet();
        }
    }
}
