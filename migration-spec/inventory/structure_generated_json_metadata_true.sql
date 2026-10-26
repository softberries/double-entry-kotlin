--
-- PostgreSQL database dump
--

\restrict ts25gJoITdn8XOSkGXgWv1prQsYsPV2TzpHghYAHhkrGqNKOrVB5n02fyhH9Nah

-- Dumped from database version 17.11 (Debian 17.11-1.pgdg13+2)
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: double_entry_account_balances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.double_entry_account_balances (
    id bigint NOT NULL,
    account character varying NOT NULL,
    scope character varying,
    balance bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: double_entry_account_balances_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.double_entry_account_balances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: double_entry_account_balances_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.double_entry_account_balances_id_seq OWNED BY public.double_entry_account_balances.id;


--
-- Name: double_entry_line_checks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.double_entry_line_checks (
    id bigint NOT NULL,
    last_line_id bigint NOT NULL,
    errors_found boolean NOT NULL,
    log text,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL
);


--
-- Name: double_entry_line_checks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.double_entry_line_checks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: double_entry_line_checks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.double_entry_line_checks_id_seq OWNED BY public.double_entry_line_checks.id;


--
-- Name: double_entry_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.double_entry_lines (
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


--
-- Name: double_entry_lines_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.double_entry_lines_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: double_entry_lines_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.double_entry_lines_id_seq OWNED BY public.double_entry_lines.id;


--
-- Name: double_entry_account_balances id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.double_entry_account_balances ALTER COLUMN id SET DEFAULT nextval('public.double_entry_account_balances_id_seq'::regclass);


--
-- Name: double_entry_line_checks id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.double_entry_line_checks ALTER COLUMN id SET DEFAULT nextval('public.double_entry_line_checks_id_seq'::regclass);


--
-- Name: double_entry_lines id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.double_entry_lines ALTER COLUMN id SET DEFAULT nextval('public.double_entry_lines_id_seq'::regclass);


--
-- Name: double_entry_account_balances double_entry_account_balances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.double_entry_account_balances
    ADD CONSTRAINT double_entry_account_balances_pkey PRIMARY KEY (id);


--
-- Name: double_entry_line_checks double_entry_line_checks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.double_entry_line_checks
    ADD CONSTRAINT double_entry_line_checks_pkey PRIMARY KEY (id);


--
-- Name: double_entry_lines double_entry_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.double_entry_lines
    ADD CONSTRAINT double_entry_lines_pkey PRIMARY KEY (id);


--
-- Name: index_account_balances_on_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX index_account_balances_on_account ON public.double_entry_account_balances USING btree (account);


--
-- Name: index_account_balances_on_scope_and_account; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX index_account_balances_on_scope_and_account ON public.double_entry_account_balances USING btree (scope, account);


--
-- Name: line_checks_created_at_last_line_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX line_checks_created_at_last_line_id_idx ON public.double_entry_line_checks USING btree (created_at, last_line_id);


--
-- Name: lines_account_code_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX lines_account_code_created_at_idx ON public.double_entry_lines USING btree (account, code, created_at);


--
-- Name: lines_account_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX lines_account_created_at_idx ON public.double_entry_lines USING btree (account, created_at);


--
-- Name: lines_scope_account_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX lines_scope_account_created_at_idx ON public.double_entry_lines USING btree (scope, account, created_at);


--
-- Name: lines_scope_account_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX lines_scope_account_id_idx ON public.double_entry_lines USING btree (scope, account, id);


--
-- PostgreSQL database dump complete
--

\unrestrict ts25gJoITdn8XOSkGXgWv1prQsYsPV2TzpHghYAHhkrGqNKOrVB5n02fyhH9Nah

