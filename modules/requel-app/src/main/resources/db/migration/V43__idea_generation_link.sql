-- Add generation_id column to ideas table to link with AI generation service
ALTER TABLE ideas ADD COLUMN generation_id VARCHAR(36);
CREATE INDEX idx_ideas_generation ON ideas (generation_id);
