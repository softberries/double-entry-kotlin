-- double_entry baseline schema (BR-100, BR-102; D-003).
-- Copied from migration-spec/inventory/structure_generated_json_metadata_true.sql, the pg_dump of the schema that
-- `rails generate double_entry:install` (default --json-metadata) creates on PostgreSQL 17
-- (lib/generators/double_entry/install/templates/migration.rb:1-50). Only tables, sequences, defaults, primary keys and
-- indexes; no schema_migrations/ar_internal_metadata. Names are unqualified so Flyway's target schema is used.
-- double_entry_line_metadata (--no-json-metadata) is deliberately absent (X-02, D-004).
-- Existing Ruby-created databases are baselined at version 1, so this script never runs on them (D-003).

CREATE TABLE double_entry_account_balances (
    id bigint NOT NULL,
    account character varying NOT NULL,
    scope character varying,
    balance bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);

CREATE SEQUENCE double_entry_account_balances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE double_entry_account_balances_id_seq OWNED BY double_entry_account_balances.id;

CREATE TABLE double_entry_line_checks (
    id bigint NOT NULL,
    last_line_id bigint NOT NULL,
    errors_found boolean NOT NULL,
    log text,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);

CREATE SEQUENCE double_entry_line_checks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE double_entry_line_checks_id_seq OWNED BY double_entry_line_checks.id;

CREATE TABLE double_entry_lines (
    id bigint NOT NULL,
    account character varying NOT NULL,
    scope character varying,
    code character varying NOT NULL,
    amount bigint NOT NULL,
    balance bigint NOT NULL,
    partner_id bigint,
    partner_account character varying NOT NULL,
    partner_scope character varying,
    detail_type character varying,
    detail_id bigint,
    metadata jsonb,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);

CREATE SEQUENCE double_entry_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE double_entry_lines_id_seq OWNED BY double_entry_lines.id;

ALTER TABLE ONLY double_entry_account_balances ALTER COLUMN id SET DEFAULT nextval('double_entry_account_balances_id_seq'::regclass);

ALTER TABLE ONLY double_entry_line_checks ALTER COLUMN id SET DEFAULT nextval('double_entry_line_checks_id_seq'::regclass);

ALTER TABLE ONLY double_entry_lines ALTER COLUMN id SET DEFAULT nextval('double_entry_lines_id_seq'::regclass);

ALTER TABLE ONLY double_entry_account_balances
    ADD CONSTRAINT double_entry_account_balances_pkey PRIMARY KEY (id);

ALTER TABLE ONLY double_entry_line_checks
    ADD CONSTRAINT double_entry_line_checks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY double_entry_lines
    ADD CONSTRAINT double_entry_lines_pkey PRIMARY KEY (id);

CREATE INDEX index_account_balances_on_account ON double_entry_account_balances USING btree (account);

CREATE UNIQUE INDEX index_account_balances_on_scope_and_account ON double_entry_account_balances USING btree (scope, account);

CREATE INDEX line_checks_created_at_last_line_id_idx ON double_entry_line_checks USING btree (created_at, last_line_id);

CREATE INDEX lines_account_code_created_at_idx ON double_entry_lines USING btree (account, code, created_at);

CREATE INDEX lines_account_created_at_idx ON double_entry_lines USING btree (account, created_at);

CREATE INDEX lines_scope_account_created_at_idx ON double_entry_lines USING btree (scope, account, created_at);

CREATE INDEX lines_scope_account_id_idx ON double_entry_lines USING btree (scope, account, id);
