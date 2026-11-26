package com.baotin.search;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator only. No web controller exposes these operations. */
final class RebuildManager {
    record Run(UUID id,String status,Instant startedAt,long captureSequence,long checkpoint,Long records) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ProjectionWriter writer;
    RebuildManager(JdbcTemplate jdbc) {
        this.jdbc=jdbc;this.transactions=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        this.writer=new ProjectionWriter(jdbc);
    }
    private void operator() {
        if(!"baotin_search_migrator".equals(jdbc.queryForObject("SELECT current_user",String.class)))
            throw new IllegalStateException("Owning Search operator role required");
    }
    Run begin() {
        operator();return transactions.execute(tx->{
            // Drain all document writers so no uncommitted lower sequence can cross the barrier.
            jdbc.execute("LOCK TABLE search.search_documents IN SHARE MODE");
            long sequence=jdbc.queryForObject("SELECT coalesce(max(sequence),0) FROM search.projection_journal",Long.class);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO search.rebuild_runs(id,status,capture_sequence) VALUES (?,'BUILDING',?)",id,sequence);
            return view(id,false);
        });
    }
    Run status(UUID id) { operator();return view(id,false); }
    private Run view(UUID id,boolean lock) {
        return jdbc.queryForObject("SELECT * FROM search.rebuild_runs WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->
            new Run(r.getObject("id",UUID.class),r.getString("status"),r.getTimestamp("started_at").toInstant(),
                r.getLong("capture_sequence"),r.getLong("checkpoint"),(Long)r.getObject("record_count")),id);
    }
    Run resume(UUID id,Path manifest,int batch) throws IOException {
        operator();if(batch<1 || batch>10000)throw new IllegalArgumentException("Batch must be1..10000");
        Run before=view(id,false);
        if(!Set.of("BUILDING","READY").contains(before.status()))throw new IllegalStateException("Run is not importable");
        RebuildManifest input=RebuildManifest.load(manifest,id,before.startedAt());
        bind(id,input);
        if(before.status().equals("READY"))return view(id,false);
        long lineNumber=0;int consumed=0;
        try(Reader reader=new BufferedReader(Files.newBufferedReader(input.data(),StandardCharsets.UTF_8))) {
            String line;
            while((line=RebuildManifest.line(reader))!=null) {
                lineNumber++;
                if(lineNumber>input.records())throw new IllegalArgumentException("Snapshot record count exceeded");
                if(lineNumber<=before.checkpoint())continue;
                ProjectionChange change=RebuildManifest.change(line);long expected=lineNumber-1;
                transactions.executeWithoutResult(tx->{
                    Run current=view(id,true);
                    if(!current.status().equals("BUILDING") || current.checkpoint()!=expected)throw new IllegalStateException("Concurrent resume rejected");
                    writer.apply(id,change);
                    jdbc.update("UPDATE search.rebuild_runs SET checkpoint=? WHERE id=?",expected+1,id);
                });
                consumed++;
                if(consumed==batch && lineNumber<input.records())return view(id,false);
            }
        }
        if(lineNumber!=input.records() || !RebuildManifest.digest(input.data()).equals(input.fileHash()))
            throw new IllegalArgumentException("Snapshot EOF/count/hash mismatch");
        return transactions.execute(tx->{
            Run current=view(id,true);
            if(!current.status().equals("BUILDING") || current.checkpoint()!=input.records())throw new IllegalStateException("Incomplete snapshot");
            jdbc.update("UPDATE search.rebuild_runs SET status='READY' WHERE id=?",id);return view(id,false);
        });
    }
    private void bind(UUID id,RebuildManifest input) {
        transactions.executeWithoutResult(tx->{
            Run locked=view(id,true);if(!Set.of("BUILDING","READY").contains(locked.status()))throw new IllegalStateException("Terminal run input cannot change");
            String existing=jdbc.queryForObject("SELECT manifest_hash FROM search.rebuild_runs WHERE id=?",String.class,id);
            if(existing!=null && !existing.equals(input.manifestHash()))throw new IllegalArgumentException("Run input is immutable");
            jdbc.update("UPDATE search.rebuild_runs SET manifest_hash=?,file_hash=?,record_count=? WHERE id=?",input.manifestHash(),input.fileHash(),input.records(),id);
        });
    }
    Run cutover(UUID id) {
        operator();return transactions.execute(tx->{
            Run current=view(id,true);if(!current.status().equals("READY"))throw new IllegalStateException("Only READY can cut over");
            // Same lock order as live transactions: Inbox -> documents -> journal.
            jdbc.execute("LOCK TABLE search.inbox_events,search.search_documents,search.projection_journal IN ACCESS EXCLUSIVE MODE");
            long high=jdbc.queryForObject("SELECT coalesce(max(sequence),0) FROM search.projection_journal",Long.class);
            RebuildMerger merger=new RebuildMerger(jdbc);
            jdbc.query(connection->{
                var statement=connection.prepareStatement("SELECT document_id,deleted,snapshot::text FROM search.projection_journal WHERE sequence>? AND sequence<=? ORDER BY sequence");
                statement.setLong(1,current.captureSequence());statement.setLong(2,high);statement.setFetchSize(500);return statement;
            },(org.springframework.jdbc.core.RowCallbackHandler)r->merger.merge(id,r.getObject(1,UUID.class),r.getBoolean(2),r.getString(3)));
            // DELETE preserves pre-cutover MVCC reader snapshots; TRUNCATE would not.
            jdbc.update("DELETE FROM search.search_documents");
            jdbc.update("INSERT INTO search.search_documents("+RebuildMerger.COLUMNS+") SELECT "+RebuildMerger.COLUMNS+" FROM search.rebuild_documents WHERE run_id=?",id);
            jdbc.update("INSERT INTO search.inbox_events(event_id) SELECT event_id FROM search.rebuild_inbox WHERE run_id=? ON CONFLICT DO NOTHING",id);
            jdbc.update("UPDATE search.rebuild_runs SET status='COMPLETED',caught_up_sequence=?,completed_at=clock_timestamp() WHERE id=?",high,id);
            return view(id,false);
        });
    }
    Run cancel(UUID id) {
        operator();return transactions.execute(tx->{
            Run run=view(id,true);if(!Set.of("BUILDING","READY").contains(run.status()))throw new IllegalStateException("Run is already terminal");
            jdbc.update("UPDATE search.rebuild_runs SET status='CANCELLED',completed_at=clock_timestamp() WHERE id=?",id);return view(id,false);
        });
    }
    void purge(UUID id) {
        operator();transactions.executeWithoutResult(tx->{
            Run run=view(id,true);if(!Set.of("COMPLETED","CANCELLED").contains(run.status()))throw new IllegalStateException("Live staging must be retained");
            jdbc.update("DELETE FROM search.rebuild_documents WHERE run_id=?",id);jdbc.update("DELETE FROM search.rebuild_inbox WHERE run_id=?",id);
        });
    }
}
