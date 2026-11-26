package com.baotin.search;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Fixed own-schema destinations only; callers provide the surrounding transaction. */
final class ProjectionWriter {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    ProjectionWriter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    void apply(UUID run,ProjectionChange change) {
        int inserted=run==null
            ? jdbc.update("INSERT INTO search.inbox_events(event_id) VALUES (?) ON CONFLICT DO NOTHING",change.event())
            : jdbc.update("INSERT INTO search.rebuild_inbox(run_id,event_id) VALUES (?,?) ON CONFLICT DO NOTHING",run,change.event());
        if(inserted==0) return;
        if(change.score()!=null) risk(run,change);
        else if(change.publication()!=null) publish(run,change);
    }
    private String table(UUID run) { return run==null?"search.search_documents":"search.rebuild_documents"; }
    private String conflict(UUID run) { return run==null?"id":"run_id,id"; }
    private List<Object> args(UUID run) {
        List<Object> args=new ArrayList<>();if(run!=null)args.add(run);return args;
    }
    private void publish(UUID run,ProjectionChange c) {
        Publication p=c.publication();String t=table(run);List<Object> args=args(run);
        Collections.addAll(args,c.documentId(),c.type(),c.reference(),p.title(),p.display(),p.searchText(),p.status(),
            json.writeValueAsString(p.metadata()),p.lookupHash(),Timestamp.from(c.at()),c.event(),Timestamp.from(c.at()),p.content());
        jdbc.update("INSERT INTO "+t+"("+(run==null?"":"run_id,")+"id,document_type,reference_id,title,display_value,search_text,status,metadata,lookup_hash,target_at,target_event,updated_at,public_content) VALUES ("+
            (run==null?"":"?,")+"?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?) ON CONFLICT("+conflict(run)+") DO UPDATE SET "+
            "public_content=EXCLUDED.public_content,title=EXCLUDED.title,display_value=EXCLUDED.display_value,search_text=EXCLUDED.search_text,status=EXCLUDED.status,metadata=EXCLUDED.metadata,lookup_hash=EXCLUDED.lookup_hash,target_at=EXCLUDED.target_at,target_event=EXCLUDED.target_event,updated_at=greatest("+t+".updated_at,EXCLUDED.updated_at) "+
            "WHERE "+t+".reference_id=EXCLUDED.reference_id AND "+t+".document_type=EXCLUDED.document_type AND ("+t+".target_at IS NULL OR ("+t+".target_at,"+t+".target_event)<(EXCLUDED.target_at,EXCLUDED.target_event))",args.toArray());
    }
    private void risk(UUID run,ProjectionChange c) {
        String t=table(run);List<Object> args=args(run);
        Collections.addAll(args,c.documentId(),c.type(),c.reference(),c.score(),c.level(),Timestamp.from(c.at()),c.event(),Timestamp.from(c.at()));
        jdbc.update("INSERT INTO "+t+"("+(run==null?"":"run_id,")+"id,document_type,reference_id,risk_score,risk_level,risk_at,risk_event,updated_at) VALUES ("+
            (run==null?"":"?,")+"?,?,?,?,?,?,?,?) ON CONFLICT("+conflict(run)+") DO UPDATE SET "+
            "risk_score=EXCLUDED.risk_score,risk_level=EXCLUDED.risk_level,risk_at=EXCLUDED.risk_at,risk_event=EXCLUDED.risk_event,updated_at=greatest("+t+".updated_at,EXCLUDED.updated_at) "+
            "WHERE "+t+".reference_id=EXCLUDED.reference_id AND "+t+".document_type=EXCLUDED.document_type AND ("+t+".risk_at IS NULL OR ("+t+".risk_at,"+t+".risk_event)<(EXCLUDED.risk_at,EXCLUDED.risk_event))",args.toArray());
    }
}
