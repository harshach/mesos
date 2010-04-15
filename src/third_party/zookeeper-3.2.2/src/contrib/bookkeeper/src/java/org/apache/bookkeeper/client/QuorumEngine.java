package org.apache.bookkeeper.client;
/*
 * 
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
 * 
 */


import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.client.ClientCBWorker;
import org.apache.bookkeeper.client.QuorumOpMonitor;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.AsyncCallback.ReadCallback;
import org.apache.bookkeeper.client.QuorumOpMonitor.PendingOp;
import org.apache.bookkeeper.client.QuorumOpMonitor.PendingReadOp;
import org.apache.bookkeeper.proto.ReadEntryCallback;
import org.apache.bookkeeper.proto.WriteCallback;
import org.apache.log4j.Logger;



/**
 * Implements the quorum protocol.It basically handles requests coming 
 * from BookKeeper and forward to the appropriate BookieHandle objects.
 */

public class QuorumEngine {
    static Logger LOG = Logger.getLogger(QuorumEngine.class);

    QuorumOpMonitor opMonitor;
    ClientCBWorker cbWorker;
    LedgerHandle lh;
    int qRef = 0;
    
    /**
     * Operation descriptor: Requests generated by BookKeeper.java
     * upon client calls. There are three types of requests: READ, 
     * ADD, STOP.
     */
    
    static long idCounter; 
    static synchronized long getOpId(){
        return idCounter++;
    }
    
    public static class Operation {
        public static final int READ = 0;
        public static final int ADD = 1;
        public static final int STOP = 2;
        
        
        int type;
        LedgerHandle ledger;
        long id;
        int rc = 0;
        boolean ready = false;
        
         public Operation(){
             this.id = getOpId();
         }
            
         long getId(){
             return id;
         }
            

        public static class AddOp extends Operation {
            AddCallback cb;
            Object ctx;
            byte[] data;
            long entry;
            
            public AddOp(LedgerHandle ledger, byte[] data, AddCallback cb, Object ctx){
                type = Operation.ADD;
            
                this.data = data;
                this.entry = ledger.incLast(); 
                this.cb = cb;
                this.ctx = ctx;
                
                this.ledger = ledger;
            }
            
        }
        
        
        public static class ReadOp extends Operation {
            ReadCallback cb;
            Object ctx;
            long firstEntry;
            long lastEntry;
            LedgerEntry[] seq;
            AtomicInteger counter;
            HashMap<Long, AtomicInteger> nacks;
            //boolean complete;
            
            public ReadOp(LedgerHandle ledger, long firstEntry, long lastEntry, ReadCallback cb, Object ctx){
                type = READ;
            
                this.firstEntry = firstEntry;
                this.lastEntry = lastEntry;
                this.cb = cb;
                this.ctx = ctx;
                this.seq = new LedgerEntry[(int) (lastEntry - firstEntry + 1)];
                this.counter = new AtomicInteger(0);
                this.nacks = new HashMap<Long, AtomicInteger>();
                //this.complete = false;
                
                this.ledger = ledger;
            }
        }
        
        public static class StopOp extends Operation {
            public StopOp(){
                type = STOP;
            }
        }
        
        
        
        
        void setErrorCode(int rc){
            this.rc = rc;
        }
        
        int getErrorCode(){
            return this.rc;
        }
        
        synchronized boolean isReady(){
                return ready;
        }
        
        synchronized void setReady(){    
              ready = true;
              this.notify();
        }
        
        LedgerHandle getLedger(){
            return ledger;
        }
    }
    
    
    public static class SubOp{
     int bIndex;   
     Operation op;
     
     public static class SubAddOp extends SubOp{
         PendingOp pOp;
         WriteCallback wcb;
        
         SubAddOp(Operation op, 
                 PendingOp pOp, 
                 int bIndex,
                 WriteCallback wcb){
             this.op = op;
             this.pOp = pOp;
             this.bIndex = bIndex;
             this.wcb = wcb;
         }
     }
    
     public static class SubReadOp extends SubOp{
         PendingReadOp pOp;
         ReadEntryCallback rcb;
         
         SubReadOp(Operation op, 
                 PendingReadOp pOp, 
                 int bIndex, 
                 ReadEntryCallback rcb){
             this.op = op;
             this.pOp = pOp;
             this.bIndex = bIndex;
             this.rcb = rcb;
         }
     }
     
     public static class SubStopOp extends SubOp{
         SubStopOp(Operation op){
             this.op = op;
         }
     }
    }
    
    public QuorumEngine(LedgerHandle lh){ 
        this.lh = lh;
        this.opMonitor = new QuorumOpMonitor(lh);
        QuorumEngine.idCounter = 0;
        LOG.debug("Creating cbWorker");
        this.cbWorker = ClientCBWorker.getInstance();
        LOG.debug("Created cbWorker");
    }
  
    /**
     * Sends requests to BookieHandle instances. Methods in BookKeeper call
     * this method to submit both add and read requests.
     * 
     * @param r Operation descriptor
     */
    void sendOp(Operation r)
    throws InterruptedException, BKException {
        int n;    
        
        switch(r.type){
        case Operation.READ:
            
            Operation.ReadOp rOp = (Operation.ReadOp) r;
            
            LOG.debug("Adding read operation to opMonitor: " + rOp.firstEntry + ", " + rOp.lastEntry);
            cbWorker.addOperation(r);
            
            for(long entry = rOp.firstEntry; 
            entry <= rOp.lastEntry;
            entry++){
                long counter = 0;
                PendingReadOp pROp = new PendingReadOp(lh);
                
                n = lh.getBookies(entry).size();
                if(n < lh.getQuorumSize())
                    throw BKException.create(Code.NotEnoughBookiesException);
                
                //Send requests to bookies
                while(counter < lh.getQuorumSize()){
                    int index = (int)((entry + counter++) % n);
                    try{
                        SubOp.SubReadOp sRead = new SubOp.SubReadOp(rOp, 
                                pROp, 
                                index,
                                opMonitor);
   
                        BookieHandle bh = lh.getBookies(entry).get((index) % n); 
                        if(bh.isEnabled()) bh.sendRead(lh, sRead, entry);            
                    } catch(IOException e){
                        LOG.error(e);
                    }
                }  
            }
  
            break;
        case Operation.ADD:
            n = lh.getBookies().size();

            if(n < lh.getQuorumSize())
                throw BKException.create(Code.NotEnoughBookiesException);
            
            long counter = 0;
            
            cbWorker.addOperation(r);
            Operation.AddOp aOp = (Operation.AddOp) r;
            PendingOp pOp = new PendingOp();
            ArrayList<BookieHandle> bookies;
            
            while(counter < lh.getQuorumSize()  ){
                int index = (int)((aOp.entry + counter++) % n);
                
                try{
                    SubOp.SubAddOp sAdd = new 
                    SubOp.SubAddOp(aOp, 
                            pOp, 
                            index,
                            opMonitor);
                   
                    lh.getBookies().get((index) % n).sendAdd(lh, sAdd, aOp.entry);
                } catch (Exception io) {
                    LOG.error("Error when sending entry: " + aOp.entry + ", " + index + ", " + io);
                    counter--;
                    n = lh.getBookies().size();
                }
            }
            break;
                case Operation.STOP:
                    cbWorker.shutdown();
                    break;
        }
    }
    
}