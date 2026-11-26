package com.baotin.search;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Apply safe committed row snapshots; publication and risk clocks remain independent. */
final class RebuildMerger {
    static final String COLUMNS="id,document_type,reference_id,title,display_value,search_text,status,metadata,lookup_hash,target_at,target_event,risk_score,risk_level,risk_at,risk_event,updated_at,public_content";
    private final JdbcTemplate jdbc;
    RebuildMerger(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    void merge(UUID run,UUID document,boolean deleted,String snapshot) {
        if(deleted) { jdbc.update("DELETE FROM search.rebuild_documents WHERE run_id=? AND id=?",run,document);return; }
        String table="search.rebuild_documents";
        String target="EXCLUDED.target_at IS NOT NULL AND ("+table+".target_at IS NULL OR ("+table+".target_at,"+table+".target_event)<(EXCLUDED.target_at,EXCLUDED.target_event))";
        String risk="EXCLUDED.risk_at IS NOT NULL AND ("+table+".risk_at IS NULL OR ("+table+".risk_at,"+table+".risk_event)<(EXCLUDED.risk_at,EXCLUDED.risk_event))";
        List<String> assignments=new ArrayList<>();
        for(String column:List.of("title","display_value","search_text","status","metadata","lookup_hash","public_content","target_at","target_event"))
            assignments.add(column+"=CASE WHEN "+target+" THEN EXCLUDED."+column+" ELSE "+table+"."+column+" END");
        for(String column:List.of("risk_score","risk_level","risk_at","risk_event"))
            assignments.add(column+"=CASE WHEN "+risk+" THEN EXCLUDED."+column+" ELSE "+table+"."+column+" END");
        assignments.add("updated_at=greatest("+table+".updated_at,EXCLUDED.updated_at)");
        jdbc.update("INSERT INTO "+table+"(run_id,"+COLUMNS+") SELECT ?,"+COLUMNS+" FROM jsonb_populate_record(NULL::search.search_documents,?::jsonb) AS snapshot "+
            "ON CONFLICT(run_id,id) DO UPDATE SET "+String.join(",",assignments)+
            " WHERE "+table+".reference_id=EXCLUDED.reference_id AND "+table+".document_type=EXCLUDED.document_type",run,snapshot);
    }
}
