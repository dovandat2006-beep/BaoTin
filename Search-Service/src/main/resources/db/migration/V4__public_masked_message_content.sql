ALTER TABLE search.search_documents ADD COLUMN public_content text CHECK(public_content IS NULL OR length(public_content)<=10000);
ALTER TABLE search.rebuild_documents ADD COLUMN public_content text CHECK(public_content IS NULL OR length(public_content)<=10000);
