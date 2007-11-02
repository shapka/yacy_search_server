// indexRAMRI.java
// (C) 2005, 2006 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 2005 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.index;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import de.anomic.kelondro.kelondroBase64Order;
import de.anomic.kelondro.kelondroBufferedRA;
import de.anomic.kelondro.kelondroCloneableIterator;
import de.anomic.kelondro.kelondroException;
import de.anomic.kelondro.kelondroFixedWidthArray;
import de.anomic.kelondro.kelondroMScoreCluster;
import de.anomic.kelondro.kelondroNaturalOrder;
import de.anomic.kelondro.kelondroRow;
import de.anomic.server.serverByteBuffer;
import de.anomic.server.serverFileUtils;
import de.anomic.server.serverMemory;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacySeedDB;

public final class indexRAMRI implements indexRI {

    // class variables
    private final File databaseRoot;
    protected final SortedMap cache; // wordhash-container
    private final kelondroMScoreCluster hashScore;
    private final kelondroMScoreCluster hashDate;
    private long  initTime;
    private int   cacheMaxCount;
    public  int   cacheReferenceCountLimit;
    public  long  cacheReferenceAgeLimit;
    private final serverLog log;
    private String indexArrayFileName;
    private kelondroRow payloadrow;
    private kelondroRow bufferStructureBasis;
    
    public indexRAMRI(File databaseRoot, kelondroRow payloadrow, int wCacheReferenceCountLimitInit, long wCacheReferenceAgeLimitInit, String dumpname, serverLog log) {

        // creates a new index cache
        // the cache has a back-end where indexes that do not fit in the cache are flushed
        this.databaseRoot = databaseRoot;
        this.cache = Collections.synchronizedSortedMap(new TreeMap());
        this.hashScore = new kelondroMScoreCluster();
        this.hashDate  = new kelondroMScoreCluster();
        this.initTime = System.currentTimeMillis();
        this.cacheMaxCount = 10000;
        this.cacheReferenceCountLimit = wCacheReferenceCountLimitInit;
        this.cacheReferenceAgeLimit = wCacheReferenceAgeLimitInit;
        this.log = log;
        this.indexArrayFileName = dumpname;
        this.payloadrow = payloadrow;
        this.bufferStructureBasis = new kelondroRow(
                "byte[] wordhash-" + yacySeedDB.commonHashLength + ", " +
                "Cardinal occ-4 {b256}, " +
                "Cardinal time-8 {b256}, " +
                "byte[] urlprops-" + payloadrow.objectsize(),
                kelondroBase64Order.enhancedCoder, 0);
        
        // read in dump of last session
        try {
            restore();
        } catch (IOException e){
            log.logSevere("unable to restore cache dump: " + e.getMessage(), e);
        }
    }

    public int minMem() {
        // there is no specific large array that needs to be maintained
        // this value is just a guess of the possible overhead
        return 100 * 1024; // 100 kb
    }
    
    public synchronized long getUpdateTime(String wordHash) {
        indexContainer entries = getContainer(wordHash, null);
        if (entries == null) return 0;
        return entries.updated();
    }
    
    private void dump() throws IOException {
        log.logConfig("creating dump for index cache '" + indexArrayFileName + "', " + cache.size() + " words (and much more urls)");
        File indexDumpFile = new File(databaseRoot, indexArrayFileName);
        if (indexDumpFile.exists()) indexDumpFile.delete();
        kelondroFixedWidthArray dumpArray = null;
        kelondroBufferedRA writeBuffer = null;
        if (false /*serverMemory.available() > 50 * bufferStructureBasis.objectsize() * cache.size()*/) {
            writeBuffer = new kelondroBufferedRA();
            dumpArray = new kelondroFixedWidthArray(writeBuffer, indexDumpFile.getCanonicalPath(), bufferStructureBasis, 0);
            log.logInfo("started dump of ram cache: " + cache.size() + " words; memory-enhanced write");
        } else {
            dumpArray = new kelondroFixedWidthArray(indexDumpFile, bufferStructureBasis, 0);
            log.logInfo("started dump of ram cache: " + cache.size() + " words; low-memory write");
        }
        long startTime = System.currentTimeMillis();
        long messageTime = System.currentTimeMillis() + 5000;
        long wordsPerSecond = 0, wordcount = 0, urlcount = 0;
        Map.Entry entry;
        String wordHash;
        indexContainer container;
        long updateTime;
        indexRWIEntry iEntry;
        kelondroRow.Entry row = dumpArray.row().newEntry();
        byte[] occ, time;

        // write wCache
        synchronized (cache) {
            Iterator i = cache.entrySet().iterator();
            while (i.hasNext()) {
                // get entries
                entry = (Map.Entry) i.next();
                wordHash = (String) entry.getKey();
                updateTime = getUpdateTime(wordHash);
                container = (indexContainer) entry.getValue();

                // put entries on stack
                if (container != null) {
                    Iterator ci = container.entries();
                    occ = kelondroNaturalOrder.encodeLong(container.size(), 4);
                    time = kelondroNaturalOrder.encodeLong(updateTime, 8);
                    while (ci.hasNext()) {
                        iEntry = (indexRWIEntry) ci.next();
                        row.setCol(0, wordHash.getBytes());
                        row.setCol(1, occ);
                        row.setCol(2, time);
                        row.setCol(3, iEntry.toKelondroEntry().bytes());
                        dumpArray.set((int) urlcount++, row);
                    }
                }
                wordcount++;
                i.remove(); // free some mem

                // write a log
                if (System.currentTimeMillis() > messageTime) {
                    serverMemory.gc(1000, "indexRAMRI, for better statistic-1"); // for better statistic - thq
                    wordsPerSecond = wordcount * 1000
                            / (1 + System.currentTimeMillis() - startTime);
                    log.logInfo("dump status: " + wordcount
                            + " words done, "
                            + (cache.size() / (wordsPerSecond + 1))
                            + " seconds remaining, free mem = "
                            + (Runtime.getRuntime().freeMemory() / 1024 / 1024)
                            + "MB");
                    messageTime = System.currentTimeMillis() + 5000;
                }
            }
        }
        if (writeBuffer != null) {
            serverByteBuffer bb = writeBuffer.getBuffer();
            //System.out.println("*** byteBuffer size = " + bb.length());
            serverFileUtils.write(bb.getBytes(), indexDumpFile);
            writeBuffer.close();
        }
        dumpArray.close();
        dumpArray = null;
        log.logInfo("finished dump of ram cache: " + urlcount + " word/URL relations in " + ((System.currentTimeMillis() - startTime) / 1000) + " seconds");
    }

    private long restore() throws IOException {
        File indexDumpFile = new File(databaseRoot, indexArrayFileName);
        if (!(indexDumpFile.exists())) return 0;
        kelondroFixedWidthArray dumpArray;
        kelondroBufferedRA readBuffer = null;
        if (false /*serverMemory.available() > indexDumpFile.length() * 2*/) {
            readBuffer = new kelondroBufferedRA(new serverByteBuffer(serverFileUtils.read(indexDumpFile)));
            dumpArray = new kelondroFixedWidthArray(readBuffer, indexDumpFile.getCanonicalPath(), bufferStructureBasis, 0);
            log.logInfo("started restore of ram cache '" + indexArrayFileName + "', " + dumpArray.size() + " word/URL relations; memory-enhanced read");
        } else {
            dumpArray = new kelondroFixedWidthArray(indexDumpFile, bufferStructureBasis, 0);
            log.logInfo("started restore of ram cache '" + indexArrayFileName + "', " + dumpArray.size() + " word/URL relations; low-memory read");
        }
        long startTime = System.currentTimeMillis();
        long messageTime = System.currentTimeMillis() + 5000;
        long urlCount = 0, urlsPerSecond = 0;
        try {
            synchronized (cache) {
                Iterator i = dumpArray.contentRows(-1);
                String wordHash;
                //long creationTime;
                indexRWIEntry wordEntry;
                kelondroRow.EntryIndex row;
                //Runtime rt = Runtime.getRuntime();
                while (i.hasNext()) {
                    // get out one entry
                    row = (kelondroRow.EntryIndex) i.next();
                    if ((row == null) || (row.empty(0)) || (row.empty(3))) continue;
                    wordHash = row.getColString(0, "UTF-8");
                    //creationTime = kelondroRecords.bytes2long(row[2]);
                    wordEntry = new indexRWIEntry(row.getColBytes(3));
                    // store to cache
                    addEntry(wordHash, wordEntry, startTime, false);
                    urlCount++;
                    // protect against memory shortage
                    //while (rt.freeMemory() < 1000000) {flushFromMem(); java.lang.System.gc();}
                    // write a log
                    if (System.currentTimeMillis() > messageTime) {
                        serverMemory.gc(1000, "indexRAMRI, for better statistic-2"); // for better statistic - thq
                        urlsPerSecond = 1 + urlCount * 1000 / (1 + System.currentTimeMillis() - startTime);
                        log.logInfo("restoring status: " + urlCount + " urls done, " + ((dumpArray.size() - urlCount) / urlsPerSecond) + " seconds remaining, free mem = " + (Runtime.getRuntime().freeMemory() / 1024 / 1024) + "MB");
                        messageTime = System.currentTimeMillis() + 5000;
                    }
                }
            }
            if (readBuffer != null) readBuffer.close();
            dumpArray.close();
            dumpArray = null;
            log.logConfig("finished restore: " + cache.size() + " words in " + ((System.currentTimeMillis() - startTime) / 1000) + " seconds");
        } catch (kelondroException e) {
            // restore failed
            log.logSevere("failed restore of indexCache array dump: " + e.getMessage(), e);
        } finally {
            if (dumpArray != null) try {dumpArray.close();}catch(Exception e){}
        }
        return urlCount;
    }

    // cache settings

    public int maxURLinCache() {
        if (hashScore.size() == 0) return 0;
        return hashScore.getMaxScore();
    }

    public long minAgeOfCache() {
        if (hashDate.size() == 0) return 0;
        return System.currentTimeMillis() - longEmit(hashDate.getMaxScore());
    }

    public long maxAgeOfCache() {
        if (hashDate.size() == 0) return 0;
        return System.currentTimeMillis() - longEmit(hashDate.getMinScore());
    }

    public void setMaxWordCount(int maxWords) {
        this.cacheMaxCount = maxWords;
    }
    
    public int getMaxWordCount() {
        return this.cacheMaxCount;
    }
    
    public int size() {
        return cache.size();
    }

    public synchronized int indexSize(String wordHash) {
        indexContainer cacheIndex = (indexContainer) cache.get(wordHash);
        if (cacheIndex == null) return 0;
        return cacheIndex.size();
    }

    public synchronized kelondroCloneableIterator wordContainers(String startWordHash, boolean rot) {
        // we return an iterator object that creates top-level-clones of the indexContainers
        // in the cache, so that manipulations of the iterated objects do not change
        // objects in the cache.
        return new wordContainerIterator(startWordHash, rot);
    }

    public class wordContainerIterator implements kelondroCloneableIterator {

        // this class exists, because the wCache cannot be iterated with rotation
        // and because every indeContainer Object that is iterated must be returned as top-level-clone
        // so this class simulates wCache.tailMap(startWordHash).values().iterator()
        // plus the mentioned features
        
        private boolean rot;
        private Iterator iterator;
        
        public wordContainerIterator(String startWordHash, boolean rot) {
            this.rot = rot;
            this.iterator = (startWordHash == null) ? cache.values().iterator() : cache.tailMap(startWordHash).values().iterator();
            // The collection's iterator will return the values in the order that their corresponding keys appear in the tree.
        }
        
        public Object clone(Object secondWordHash) {
            return new wordContainerIterator((String) secondWordHash, rot);
        }
        
        public boolean hasNext() {
            if (rot) return true;
            return iterator.hasNext();
        }

        public Object next() {
            if (iterator.hasNext()) {
                return ((indexContainer) iterator.next()).topLevelClone();
            } else {
                // rotation iteration
                if (rot) {
                    iterator = cache.values().iterator();
                    return ((indexContainer) iterator.next()).topLevelClone();
                } else {
                    return null;
                }
            }
        }

        public void remove() {
            iterator.remove();
        }
        
    }

    public synchronized String maxScoreWordHash() {
        if (cache.size() == 0) return null;
        try {
            return (String) hashScore.getMaxObject();
        } catch (Exception e) {
            log.logSevere("flushFromMem: " + e.getMessage(), e);
        }
        return null;
    }
    
    public synchronized String bestFlushWordHash() {
        // select appropriate hash
        // we have 2 different methods to find a good hash:
        // - the oldest entry in the cache
        // - the entry with maximum count
        if (cache.size() == 0) return null;
        try {
                String hash = null;
                int count = hashScore.getMaxScore();
                if ((count >= cacheReferenceCountLimit) &&
                    ((hash = (String) hashScore.getMaxObject()) != null)) {
                    // we MUST flush high-score entries, because a loop deletes entries in cache until this condition fails
                    // in this cache we MUST NOT check wCacheMinAge
                    return hash;
                }
                long oldestTime = longEmit(hashDate.getMinScore());
                if (((System.currentTimeMillis() - oldestTime) > cacheReferenceAgeLimit) &&
                    ((hash = (String) hashDate.getMinObject()) != null)) {
                    // flush out-dated entries
                    return hash;
                }
                // cases with respect to memory situation
                if (Runtime.getRuntime().freeMemory() < 100000) {
                    // urgent low-memory case
                    hash = (String) hashScore.getMaxObject(); // flush high-score entries (saves RAM)
                } else {
                    // not-efficient-so-far case. cleans up unnecessary cache slots
                    hash = (String) hashDate.getMinObject(); // flush oldest entries
                }
                return hash;
        } catch (Exception e) {
            log.logSevere("flushFromMem: " + e.getMessage(), e);
        }
        return null;
    }

    private int intTime(long longTime) {
        return (int) Math.max(0, ((longTime - initTime) / 1000));
    }

    private long longEmit(int intTime) {
        return (((long) intTime) * (long) 1000) + initTime;
    }
    
    public boolean hasContainer(String wordHash) {
        return cache.containsKey(wordHash);
    }
    
    public int sizeContainer(String wordHash) {
        return ((indexContainer) cache.get(wordHash)).size();
    }

    public synchronized indexContainer getContainer(String wordHash, Set urlselection) {
        if (wordHash == null) return null;
        
        // retrieve container
        indexContainer container = (indexContainer) cache.get(wordHash);
        
        // We must not use the container from cache to store everything we find,
        // as that container remains linked to in the cache and might be changed later
        // while the returned container is still in use.
        // create a clone from the container
        if (container != null) container = container.topLevelClone();
        
        // select the urlselection
        if ((urlselection != null) && (container != null)) container.select(urlselection);

        return container;
    }

    public synchronized indexContainer deleteContainer(String wordHash) {
        // returns the index that had been deleted
        indexContainer container = (indexContainer) cache.remove(wordHash);
        hashScore.deleteScore(wordHash);
        hashDate.deleteScore(wordHash);
        return container;
    }

    public synchronized boolean removeEntry(String wordHash, String urlHash) {
        indexContainer c = (indexContainer) cache.get(wordHash);
        if ((c != null) && (c.remove(urlHash) != null)) {
            // removal successful
            if (c.size() == 0) {
                deleteContainer(wordHash);
            } else {
                cache.put(wordHash, c);
                hashScore.decScore(wordHash);
                hashDate.setScore(wordHash, intTime(System.currentTimeMillis()));
            }
            return true;
        }
        return false;
    }
    
    public synchronized int removeEntries(String wordHash, Set urlHashes) {
        if (urlHashes.size() == 0) return 0;
        indexContainer c = (indexContainer) cache.get(wordHash);
        int count;
        if ((c != null) && ((count = c.removeEntries(urlHashes)) > 0)) {
            // removal successful
            if (c.size() == 0) {
                deleteContainer(wordHash);
            } else {
                cache.put(wordHash, c);
                hashScore.setScore(wordHash, c.size());
                hashDate.setScore(wordHash, intTime(System.currentTimeMillis()));
            }
            return count;
        }
        return 0;
    }
 
    public synchronized int tryRemoveURLs(String urlHash) {
        // this tries to delete an index from the cache that has this
        // urlHash assigned. This can only work if the entry is really fresh
        // Such entries must be searched in the latest entries
        int delCount = 0;
            Iterator i = cache.entrySet().iterator();
            Map.Entry entry;
            String wordhash;
            indexContainer c;
            while (i.hasNext()) {
                entry = (Map.Entry) i.next();
                wordhash = (String) entry.getKey();
            
                // get container
                c = (indexContainer) entry.getValue();
                if (c.remove(urlHash) != null) {
                    if (c.size() == 0) {
                        i.remove();
                    } else {
                        cache.put(wordhash, c); // superfluous?
                    }
                    delCount++;
                }
            }
        return delCount;
    }
    
    public synchronized void addEntries(indexContainer container, long updateTime, boolean dhtCase) {
        // this puts the entries into the cache, not into the assortment directly
        int added = 0;
        if ((container == null) || (container.size() == 0)) return;

        // put new words into cache
        String wordHash = container.getWordHash();
        indexContainer entries = (indexContainer) cache.get(wordHash); // null pointer exception? wordhash != null! must be cache==null
        if (entries == null) {
            entries = container.topLevelClone();
            added = entries.size();
        } else {
            added = entries.putAllRecent(container);
        }
        if (added > 0) {
            cache.put(wordHash, entries);
            hashScore.addScore(wordHash, added);
            hashDate.setScore(wordHash, intTime(updateTime));
        }
        entries = null;
    }

    public synchronized void addEntry(String wordHash, indexRWIEntry newEntry, long updateTime, boolean dhtCase) {
        indexContainer container = (indexContainer) cache.get(wordHash);
        if (container == null) container = new indexContainer(wordHash, this.payloadrow, 1);
        container.put(newEntry);
        cache.put(wordHash, container);
        hashScore.incScore(wordHash);
        hashDate.setScore(wordHash, intTime(updateTime));
    }

    public synchronized void close() {
        // dump cache
        try {
            dump();
        } catch (IOException e){
            log.logSevere("unable to dump cache: " + e.getMessage(), e);
        }
    }
}
