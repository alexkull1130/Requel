ALTER TABLE workflow_requirements
    ADD COLUMN breakdown_action varchar(16),
    ADD COLUMN breakdown_feedback longtext;
