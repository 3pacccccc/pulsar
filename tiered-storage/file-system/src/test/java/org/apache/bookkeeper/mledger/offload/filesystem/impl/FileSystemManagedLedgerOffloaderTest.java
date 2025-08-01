/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.bookkeeper.mledger.offload.filesystem.impl;


import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import lombok.Cleanup;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.PulsarMockBookKeeper;
import org.apache.bookkeeper.client.api.DigestType;
import org.apache.bookkeeper.client.api.LedgerEntries;
import org.apache.bookkeeper.client.api.LedgerEntry;
import org.apache.bookkeeper.client.api.ReadHandle;
import org.apache.bookkeeper.mledger.LedgerOffloader;
import org.apache.bookkeeper.mledger.impl.LedgerOffloaderStatsImpl;
import org.apache.bookkeeper.mledger.offload.filesystem.FileStoreTestBase;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.pulsar.common.naming.TopicName;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class FileSystemManagedLedgerOffloaderTest extends FileStoreTestBase {
    private PulsarMockBookKeeper bk;
    private String managedLedgerName = "public/default/persistent/testOffload";
    private String topicName = TopicName.fromPersistenceNamingEncoding(managedLedgerName);
    private String storagePath = createStoragePath(managedLedgerName);
    private LedgerHandle lh;
    private ReadHandle toWrite;
    private final int numberOfEntries = 601;
    private  Map<String, String> map = new HashMap<>();

    @Override
    public void init() throws Exception {
        super.init();
        this.bk = new PulsarMockBookKeeper(scheduler);
        this.toWrite = buildReadHandle();
        map.put("ManagedLedgerName", managedLedgerName);
    }

    @Override
    public void cleanup() throws IOException {
        if (bk != null) {
            bk.shutdown();
        }
        super.cleanup();
    }

    private ReadHandle buildReadHandle() throws Exception {

        lh = bk.createLedger(1, 1, 1, BookKeeper.DigestType.CRC32, "foobar".getBytes());

        int i = 0;
        int blocksWritten = 1;
        while (blocksWritten <= numberOfEntries) {
            byte[] entry = ("foobar" + i).getBytes();
            blocksWritten++;
            lh.addEntry(entry);
            i++;
        }
        lh.close();

        return bk.newOpenLedgerOp().withLedgerId(lh.getId())
                .withPassword("foobar".getBytes()).withDigestType(DigestType.CRC32).execute().get();
    }

    @BeforeMethod(alwaysRun = true)
    @Override
    public void start() throws Exception {
        super.start();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testOffloadAndRead() throws Exception {
        LedgerOffloader offloader = fileSystemManagedLedgerOffloader;
        UUID uuid = UUID.randomUUID();
        offloader.offload(toWrite, uuid, map).get();
        ReadHandle toTest = offloader.readOffloaded(toWrite.getId(), uuid, map).get();
        assertEquals(toTest.getLastAddConfirmed(), toWrite.getLastAddConfirmed());
        LedgerEntries toTestEntries = toTest.read(0, numberOfEntries - 1);
        LedgerEntries toWriteEntries = toWrite.read(0, numberOfEntries - 1);
        Iterator<LedgerEntry> toTestIter = toTestEntries.iterator();
        Iterator<LedgerEntry> toWriteIter = toWriteEntries.iterator();
        while (toTestIter.hasNext()) {
            LedgerEntry toWriteEntry = toWriteIter.next();
            LedgerEntry toTestEntry = toTestIter.next();

            assertEquals(toWriteEntry.getLedgerId(), toTestEntry.getLedgerId());
            assertEquals(toWriteEntry.getEntryId(), toTestEntry.getEntryId());
            assertEquals(toWriteEntry.getLength(), toTestEntry.getLength());
            assertEquals(toWriteEntry.getEntryBuffer(), toTestEntry.getEntryBuffer());
        }
        toTestEntries.close();
        toWriteEntries.close();
        toTestEntries = toTest.read(1, numberOfEntries - 1);
        toWriteEntries = toWrite.read(1, numberOfEntries - 1);
        toTestIter = toTestEntries.iterator();
        toWriteIter = toWriteEntries.iterator();
        while (toTestIter.hasNext()) {
            LedgerEntry toWriteEntry = toWriteIter.next();
            LedgerEntry toTestEntry = toTestIter.next();

            assertEquals(toWriteEntry.getLedgerId(), toTestEntry.getLedgerId());
            assertEquals(toWriteEntry.getEntryId(), toTestEntry.getEntryId());
            assertEquals(toWriteEntry.getLength(), toTestEntry.getLength());
            assertEquals(toWriteEntry.getEntryBuffer(), toTestEntry.getEntryBuffer());
        }
        toTestEntries.close();
        toWriteEntries.close();
    }

    @Test
    public void testOffloadAndReadMetrics() throws Exception {
        LedgerOffloader offloader = fileSystemManagedLedgerOffloader;
        UUID uuid = UUID.randomUUID();
        offloader.offload(toWrite, uuid, map).get();

        LedgerOffloaderStatsImpl offloaderStats = (LedgerOffloaderStatsImpl) this.offloaderStats;
        assertTrue(offloaderStats.getOffloadError(topicName) == 0);
        assertTrue(offloaderStats.getOffloadBytes(topicName) > 0);
        assertTrue(offloaderStats.getReadLedgerLatency(topicName).count > 0);
        assertTrue(offloaderStats.getWriteStorageError(topicName) == 0);

        ReadHandle toTest = offloader.readOffloaded(toWrite.getId(), uuid, map).get();
        LedgerEntries toTestEntries = toTest.read(0, numberOfEntries - 1);
        Iterator<LedgerEntry> toTestIter = toTestEntries.iterator();
        while (toTestIter.hasNext()) {
            LedgerEntry toTestEntry = toTestIter.next();
        }
        toTestEntries.close();

        assertTrue(offloaderStats.getReadOffloadError(topicName) == 0);
        assertTrue(offloaderStats.getReadOffloadBytes(topicName) > 0);
        assertTrue(offloaderStats.getReadOffloadDataLatency(topicName).count > 0);
        assertTrue(offloaderStats.getReadOffloadIndexLatency(topicName).count > 0);
    }

    @Test
    public void testDeleteOffload() throws Exception {
        LedgerOffloader offloader = fileSystemManagedLedgerOffloader;
        UUID uuid = UUID.randomUUID();
        offloader.offload(toWrite, uuid, map).get();
        Configuration configuration = new Configuration();
        @Cleanup
        FileSystem fileSystem = FileSystem.get(new URI(getURI()), configuration);
        assertTrue(fileSystem.exists(new Path(createDataFilePath(storagePath, lh.getId(), uuid))));
        assertTrue(fileSystem.exists(new Path(createIndexFilePath(storagePath, lh.getId(), uuid))));
        offloader.deleteOffloaded(lh.getId(), uuid, map).get();
        assertFalse(fileSystem.exists(new Path(createDataFilePath(storagePath, lh.getId(), uuid))));
        assertFalse(fileSystem.exists(new Path(createIndexFilePath(storagePath, lh.getId(), uuid))));
    }

    private String createStoragePath(String managedLedgerName) {
        return basePath == null ? managedLedgerName + "/" : basePath + "/" +  managedLedgerName + "/";
    }

    private String createIndexFilePath(String storagePath, long ledgerId, UUID uuid) {
        return storagePath + ledgerId + "-" + uuid + "/index";
    }

    private String createDataFilePath(String storagePath, long ledgerId, UUID uuid) {
        return storagePath + ledgerId + "-" + uuid + "/data";
    }
}
