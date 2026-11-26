package com.baotin.search;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SearchStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public SearchStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Document(UUID id,String documentType,UUID referenceId,String title,
            String displayValue,Double riskScore,String riskLevel,String status,
            Map<String,Object> metadata,Instant updatedAt,String content) {}
    public record Page(List<Document> items,int page,int size,long totalItems,long totalPages) {}

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page find(SearchQuery query) {
        String q=SearchQuery.normalize(query.q());
        String where="status='ACTIVE' AND title IS NOT NULL AND (search_vector @@ plainto_tsquery('simple', ?) OR search_text OPERATOR(public.%) ? OR lookup_hash IN (?,?,?))";
        List<Object> args=new ArrayList<>(List.of(q,q));args.addAll(SearchTypes.lookupHashes(query.q()));
        if(query.type()!=null) { where+=" AND document_type=?";args.add(query.type()); }
        if(query.riskLevel()!=null) { where+=" AND risk_level=?";args.add(query.riskLevel()); }
        long count=jdbc.queryForObject("SELECT count(*) FROM search.search_documents WHERE "+where,Long.class,args.toArray());
        args.add(q);args.add(q);args.add(q);args.add(query.size());args.add((long)query.page()*query.size());
        List<Document> rows=jdbc.query("SELECT * FROM search.search_documents WHERE "+where+
            " ORDER BY (search_text=?) DESC,ts_rank(search_vector,plainto_tsquery('simple',?)) DESC,"+
            "public.similarity(search_text,?) DESC,updated_at DESC,id ASC LIMIT ? OFFSET ?",(r,n)->
            new Document(r.getObject("id",UUID.class),r.getString("document_type"),r.getObject("reference_id",UUID.class),
                r.getString("title"),r.getString("display_value"),(Double)r.getObject("risk_score"),r.getString("risk_level"),
                r.getString("status"),metadata(r.getString("metadata")),r.getTimestamp("updated_at").toInstant(),r.getString("public_content")),args.toArray());
        return new Page(rows,query.page(),query.size(),count,(count+query.size()-1)/query.size());
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> metadata(String value) { return (Map<String,Object>)json.readValue(value,Map.class); }
}
