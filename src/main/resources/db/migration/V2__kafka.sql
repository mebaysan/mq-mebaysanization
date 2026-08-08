-- Kafka addresses a cluster through a comma-separated bootstrap list rather than one host and port,
-- and has neither a queue manager nor a channel. Nullable, because the three JMS providers never set
-- it and the validator rejects it for them.
--
-- VARCHAR(1024) matches broker_url_override: the same "a list of endpoints someone may paste in"
-- shape, and the same length Hibernate exports for @Column(length = 1024) — which is what
-- spring.jpa.hibernate.ddl-auto=validate checks at startup.
--
-- V1 is deliberately untouched. It has already run against every existing data directory.

ALTER TABLE connection_profile ADD COLUMN bootstrap_servers VARCHAR(1024);
