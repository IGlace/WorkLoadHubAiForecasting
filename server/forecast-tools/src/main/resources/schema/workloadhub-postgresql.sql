--
-- PostgreSQL database dump
--

--
-- Name: task_service; Type: SCHEMA; Schema: -; Owner: postgres
--

CREATE SCHEMA task_service;

--
-- Name: absences; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.absences (
    date date NOT NULL,
    hours double precision NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    type character varying(20) NOT NULL,
    note character varying(255),
    CONSTRAINT absences_type_check CHECK (((type)::text = ANY ((ARRAY['VACATION'::character varying, 'SICK_LEAVE'::character varying, 'PUBLIC_HOLIDAY'::character varying, 'OTHER'::character varying])::text[])))
);

--
-- Name: holidays; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.holidays (
    active boolean NOT NULL,
    end_date date NOT NULL,
    start_date date NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    country_code character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    CONSTRAINT holidays_status_check CHECK (((status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'PENDING'::character varying])::text[]))),
    CONSTRAINT holidays_type_check CHECK (((type)::text = ANY ((ARRAY['NATIONAL'::character varying, 'RELIGIOUS'::character varying])::text[])))
);

--
-- Name: job_title_role_mappings; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.job_title_role_mappings (
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    job_title_id uuid NOT NULL,
    role_id uuid NOT NULL,
    user_id uuid
);

--
-- Name: job_titles; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.job_titles (
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    value character varying(150) NOT NULL
);

--
-- Name: notifications; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.notifications (
    is_read boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    reporter_id uuid NOT NULL,
    user_id uuid NOT NULL,
    type character varying(50) NOT NULL,
    message text NOT NULL,
    metadata text,
    CONSTRAINT notifications_type_check CHECK (((type)::text = ANY ((ARRAY['TASK_ASSIGNED'::character varying, 'TASK_STATUS_CHANGED'::character varying, 'TASK_COMMENT_ADDED'::character varying, 'TASK_DUE_SOON'::character varying, 'TASK_OVERDUE'::character varying, 'WEEK_OVERLOADED'::character varying, 'TEAM_MEMBER_ADDED'::character varying, 'TEAM_MEMBER_REMOVED'::character varying, 'TASK_UNBLOCKED'::character varying])::text[])))
);

--
-- Name: personal_leaves; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.personal_leaves (
    absence_hours double precision,
    begin_time time(0) without time zone,
    end_date date NOT NULL,
    end_time time(0) without time zone,
    start_date date NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    employee_id uuid NOT NULL,
    id uuid NOT NULL,
    note character varying(1000),
    leave_type character varying(255),
    processor character varying(255),
    status character varying(255),
    CONSTRAINT personal_leaves_leave_type_check CHECK (((leave_type)::text = ANY ((ARRAY['PAID_LEAVE'::character varying, 'VACATION_ADDITIONAL_DAY'::character varying, 'SICK_LEAVE'::character varying, 'WORK_ACCIDENT'::character varying, 'JOB_SEARCH'::character varying, 'FAMILY_EVENT'::character varying, 'UNPAID_LEAVE'::character varying])::text[]))),
    CONSTRAINT personal_leaves_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[])))
);

--
-- Name: project_history; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.project_history (
    changed_at timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    changed_by uuid NOT NULL,
    id uuid NOT NULL,
    project_id uuid,
    project_key character varying(20),
    field_name character varying(100) NOT NULL,
    new_value text,
    old_value text
);

--
-- Name: projects; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.projects (
    archived boolean DEFAULT false NOT NULL,
    archived_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL,
    next_task_number bigint DEFAULT 1 NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    version bigint,
    archived_by uuid,
    id uuid NOT NULL,
    owner_id uuid NOT NULL,
    team_id uuid,
    key character varying(20) NOT NULL,
    previous_status character varying(20),
    status character varying(20) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    CONSTRAINT projects_previous_status_check CHECK (((previous_status)::text = ANY ((ARRAY['PLANNING'::character varying, 'ACTIVE'::character varying, 'ON_HOLD'::character varying, 'COMPLETED'::character varying, 'ARCHIVED'::character varying])::text[]))),
    CONSTRAINT projects_status_check CHECK (((status)::text = ANY ((ARRAY['PLANNING'::character varying, 'ACTIVE'::character varying, 'ON_HOLD'::character varying, 'COMPLETED'::character varying, 'ARCHIVED'::character varying])::text[])))
);

--
-- Name: refresh_tokens; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.refresh_tokens (
    created_at timestamp(6) without time zone NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token character varying(500) NOT NULL
);

--
-- Name: role_change_requests; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.role_change_requests (
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    current_user_role character varying(30) NOT NULL,
    requested_role character varying(30) NOT NULL,
    job_title character varying(150),
    reviewed_by character varying(255),
    CONSTRAINT role_change_requests_current_user_role_check CHECK (((current_user_role)::text = ANY ((ARRAY['ADMIN'::character varying, 'CENTER_MANAGER'::character varying, 'SKILL_TEAM_LEADER'::character varying, 'TEAM_LEADER'::character varying, 'MEMBER'::character varying, 'VIEWER'::character varying])::text[]))),
    CONSTRAINT role_change_requests_requested_role_check CHECK (((requested_role)::text = ANY ((ARRAY['ADMIN'::character varying, 'CENTER_MANAGER'::character varying, 'SKILL_TEAM_LEADER'::character varying, 'TEAM_LEADER'::character varying, 'MEMBER'::character varying, 'VIEWER'::character varying])::text[]))),
    CONSTRAINT role_change_requests_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[])))
);

--
-- Name: sync_metadata; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.sync_metadata (
    sync_in_progress boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    last_sync_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    synced_by character varying(100),
    last_sync_result character varying(500)
);

--
-- Name: task_attachments; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.task_attachments (
    created_at timestamp(6) without time zone NOT NULL,
    file_size bigint NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    task_id uuid NOT NULL,
    uploaded_by uuid NOT NULL,
    content_type character varying(100) NOT NULL,
    storage_path character varying(500) NOT NULL,
    original_file_name character varying(255) NOT NULL,
    stored_file_name character varying(255) NOT NULL
);

--
-- Name: task_comments; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.task_comments (
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    version bigint,
    id uuid NOT NULL,
    parent_comment_id uuid,
    task_id uuid NOT NULL,
    user_id uuid NOT NULL,
    content text NOT NULL
);

--
-- Name: task_history; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.task_history (
    changed_at timestamp(6) without time zone NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    task_id uuid NOT NULL,
    user_id uuid NOT NULL,
    field_name character varying(100) NOT NULL,
    new_value text,
    old_value text
);

--
-- Name: task_statuses; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.task_statuses (
    active boolean NOT NULL,
    sort_order integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    category character varying(20) NOT NULL,
    name character varying(50) NOT NULL,
    description character varying(255),
    CONSTRAINT task_statuses_category_check CHECK (((category)::text = ANY ((ARRAY['TO_DO'::character varying, 'IN_PROGRESS'::character varying, 'DONE'::character varying])::text[])))
);

--
-- Name: task_types; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.task_types (
    active boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    subtask_type_id uuid,
    name character varying(50) NOT NULL,
    description character varying(255),
    icon character varying(255)
);

--
-- Name: tasks; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.tasks (
    archived boolean NOT NULL,
    due_date date,
    original_estimate_hrs double precision,
    planned_week date,
    remaining_estimate_hrs double precision,
    reopened_from_done boolean DEFAULT false NOT NULL,
    archived_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL,
    created_date timestamp(6) without time zone NOT NULL,
    finished_date timestamp(6) without time zone,
    last_reopened_at timestamp(6) without time zone,
    started_date timestamp(6) without time zone,
    task_number bigint,
    updated_at timestamp(6) without time zone NOT NULL,
    version bigint,
    assignee_id uuid,
    id uuid NOT NULL,
    parent_task_id uuid,
    project_id uuid NOT NULL,
    reporter_id uuid NOT NULL,
    task_status_id uuid NOT NULL,
    task_type_id uuid NOT NULL,
    priority character varying(20) NOT NULL,
    key character varying(50) NOT NULL,
    description text,
    title character varying(255) NOT NULL,
    CONSTRAINT tasks_priority_check CHECK (((priority)::text = ANY ((ARRAY['HIGHEST'::character varying, 'HIGH'::character varying, 'MEDIUM'::character varying, 'LOW'::character varying, 'LOWEST'::character varying])::text[])))
);

--
-- Name: team_capacity; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.team_capacity (
    allocated_hrs double precision NOT NULL,
    total_capacity_hrs double precision NOT NULL,
    week_start date NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    team_id uuid NOT NULL
);

--
-- Name: team_members; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.team_members (
    created_at timestamp(6) without time zone NOT NULL,
    joined_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    team_id uuid NOT NULL,
    user_id uuid NOT NULL
);

--
-- Name: teams; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.teams (
    active boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    version bigint,
    id uuid NOT NULL,
    manager_id uuid,
    parent_team_id uuid,
    name character varying(100) NOT NULL
);

--
-- Name: time_logs; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.time_logs (
    hours double precision NOT NULL,
    log_date date NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    task_id uuid NOT NULL,
    user_id uuid NOT NULL,
    note character varying(500)
);

--
-- Name: user_capacity; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.user_capacity (
    absence_hrs double precision NOT NULL,
    available_hrs double precision NOT NULL,
    base_capacity_hrs double precision NOT NULL,
    week_start date NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    user_id uuid NOT NULL
);

--
-- Name: user_roles; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.user_roles (
    active boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    code character varying(50) NOT NULL,
    label character varying(100) NOT NULL
);

--
-- Name: users; Type: TABLE; Schema: task_service; Owner: postgres
--

CREATE TABLE task_service.users (
    active boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    deactivated_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone NOT NULL,
    version bigint,
    id uuid NOT NULL,
    manager_id uuid,
    manager_object_id uuid,
    object_id uuid,
    role character varying(20) NOT NULL,
    account_name character varying(50),
    username character varying(50) NOT NULL,
    department character varying(100),
    email character varying(100) NOT NULL,
    full_name character varying(100) NOT NULL,
    job_title character varying(150),
    password character varying(255),
    CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'CENTER_MANAGER'::character varying, 'SKILL_TEAM_LEADER'::character varying, 'TEAM_LEADER'::character varying, 'MEMBER'::character varying, 'VIEWER'::character varying])::text[])))
);

--
-- Name: absences absences_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.absences
    ADD CONSTRAINT absences_pkey PRIMARY KEY (id);

--
-- Name: holidays holidays_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.holidays
    ADD CONSTRAINT holidays_pkey PRIMARY KEY (id);

--
-- Name: holidays holidays_title_start_date_end_date_country_code_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.holidays
    ADD CONSTRAINT holidays_title_start_date_end_date_country_code_key UNIQUE (title, start_date, end_date, country_code);

--
-- Name: job_title_role_mappings job_title_role_mappings_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.job_title_role_mappings
    ADD CONSTRAINT job_title_role_mappings_pkey PRIMARY KEY (id);

--
-- Name: job_titles job_titles_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.job_titles
    ADD CONSTRAINT job_titles_pkey PRIMARY KEY (id);

--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);

--
-- Name: personal_leaves personal_leaves_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.personal_leaves
    ADD CONSTRAINT personal_leaves_pkey PRIMARY KEY (id);

--
-- Name: project_history project_history_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.project_history
    ADD CONSTRAINT project_history_pkey PRIMARY KEY (id);

--
-- Name: projects projects_key_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.projects
    ADD CONSTRAINT projects_key_key UNIQUE (key);

--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);

--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

--
-- Name: refresh_tokens refresh_tokens_token_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_key UNIQUE (token);

--
-- Name: role_change_requests role_change_requests_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.role_change_requests
    ADD CONSTRAINT role_change_requests_pkey PRIMARY KEY (id);

--
-- Name: sync_metadata sync_metadata_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.sync_metadata
    ADD CONSTRAINT sync_metadata_pkey PRIMARY KEY (id);

--
-- Name: task_attachments task_attachments_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_attachments
    ADD CONSTRAINT task_attachments_pkey PRIMARY KEY (id);

--
-- Name: task_attachments task_attachments_stored_file_name_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_attachments
    ADD CONSTRAINT task_attachments_stored_file_name_key UNIQUE (stored_file_name);

--
-- Name: task_comments task_comments_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_comments
    ADD CONSTRAINT task_comments_pkey PRIMARY KEY (id);

--
-- Name: task_history task_history_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_history
    ADD CONSTRAINT task_history_pkey PRIMARY KEY (id);

--
-- Name: task_statuses task_statuses_name_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_statuses
    ADD CONSTRAINT task_statuses_name_key UNIQUE (name);

--
-- Name: task_statuses task_statuses_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_statuses
    ADD CONSTRAINT task_statuses_pkey PRIMARY KEY (id);

--
-- Name: task_types task_types_name_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_types
    ADD CONSTRAINT task_types_name_key UNIQUE (name);

--
-- Name: task_types task_types_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_types
    ADD CONSTRAINT task_types_pkey PRIMARY KEY (id);

--
-- Name: tasks tasks_key_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT tasks_key_key UNIQUE (key);

--
-- Name: tasks tasks_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT tasks_pkey PRIMARY KEY (id);

--
-- Name: team_capacity team_capacity_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_capacity
    ADD CONSTRAINT team_capacity_pkey PRIMARY KEY (id);

--
-- Name: team_capacity team_capacity_team_id_week_start_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_capacity
    ADD CONSTRAINT team_capacity_team_id_week_start_key UNIQUE (team_id, week_start);

--
-- Name: team_members team_members_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_members
    ADD CONSTRAINT team_members_pkey PRIMARY KEY (id);

--
-- Name: team_members team_members_team_id_user_id_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_members
    ADD CONSTRAINT team_members_team_id_user_id_key UNIQUE (team_id, user_id);

--
-- Name: teams teams_name_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.teams
    ADD CONSTRAINT teams_name_key UNIQUE (name);

--
-- Name: teams teams_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.teams
    ADD CONSTRAINT teams_pkey PRIMARY KEY (id);

--
-- Name: time_logs time_logs_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.time_logs
    ADD CONSTRAINT time_logs_pkey PRIMARY KEY (id);

--
-- Name: job_title_role_mappings uk2y6leuw0mawlosaynsywq3cx3; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.job_title_role_mappings
    ADD CONSTRAINT uk2y6leuw0mawlosaynsywq3cx3 UNIQUE (job_title_id, user_id);

--
-- Name: team_capacity uka1ytwsnaudxbcy7qny7qckk72; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_capacity
    ADD CONSTRAINT uka1ytwsnaudxbcy7qny7qckk72 UNIQUE (team_id, week_start);

--
-- Name: holidays ukd0p4te1pukhoqt6pyvm2yi9wb; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.holidays
    ADD CONSTRAINT ukd0p4te1pukhoqt6pyvm2yi9wb UNIQUE (title, start_date, end_date, country_code);

--
-- Name: user_capacity uki8gtu7glow0uwp4up2d5ksonj; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.user_capacity
    ADD CONSTRAINT uki8gtu7glow0uwp4up2d5ksonj UNIQUE (user_id, week_start);

--
-- Name: job_titles ukqydsfc33hake2sogd1she6ybo; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.job_titles
    ADD CONSTRAINT ukqydsfc33hake2sogd1she6ybo UNIQUE (value);

--
-- Name: team_members uks8nuwsa7nvebc246ed822w68x; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_members
    ADD CONSTRAINT uks8nuwsa7nvebc246ed822w68x UNIQUE (team_id, user_id);

--
-- Name: user_capacity user_capacity_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.user_capacity
    ADD CONSTRAINT user_capacity_pkey PRIMARY KEY (id);

--
-- Name: user_capacity user_capacity_user_id_week_start_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.user_capacity
    ADD CONSTRAINT user_capacity_user_id_week_start_key UNIQUE (user_id, week_start);

--
-- Name: user_roles user_roles_code_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.user_roles
    ADD CONSTRAINT user_roles_code_key UNIQUE (code);

--
-- Name: user_roles user_roles_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (id);

--
-- Name: users users_account_name_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.users
    ADD CONSTRAINT users_account_name_key UNIQUE (account_name);

--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

--
-- Name: users users_object_id_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.users
    ADD CONSTRAINT users_object_id_key UNIQUE (object_id);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.users
    ADD CONSTRAINT users_username_key UNIQUE (username);

--
-- Name: uq_job_titles_value_ci; Type: INDEX; Schema: task_service; Owner: postgres
--

CREATE UNIQUE INDEX uq_job_titles_value_ci ON task_service.job_titles USING btree (lower((value)::text));

--
-- Name: uq_jtrm_jobtitle_global; Type: INDEX; Schema: task_service; Owner: postgres
--

CREATE UNIQUE INDEX uq_jtrm_jobtitle_global ON task_service.job_title_role_mappings USING btree (job_title_id) WHERE (user_id IS NULL);

--
-- Name: uq_jtrm_jobtitle_user; Type: INDEX; Schema: task_service; Owner: postgres
--

CREATE UNIQUE INDEX uq_jtrm_jobtitle_user ON task_service.job_title_role_mappings USING btree (job_title_id, user_id) WHERE (user_id IS NOT NULL);

--
-- Name: refresh_tokens fk1lih5y2npsf8u5o3vhdb9y0os; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.refresh_tokens
    ADD CONSTRAINT fk1lih5y2npsf8u5o3vhdb9y0os FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: task_comments fk1q8tnnknoes9jrvibtvg4eoic; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_comments
    ADD CONSTRAINT fk1q8tnnknoes9jrvibtvg4eoic FOREIGN KEY (parent_comment_id) REFERENCES task_service.task_comments(id);

--
-- Name: task_attachments fk4eyiisq4wyx2mfj3p9h8ppufo; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_attachments
    ADD CONSTRAINT fk4eyiisq4wyx2mfj3p9h8ppufo FOREIGN KEY (task_id) REFERENCES task_service.tasks(id);

--
-- Name: users fk5p1ci5btqfwvtaqx5n2wxi182; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.users
    ADD CONSTRAINT fk5p1ci5btqfwvtaqx5n2wxi182 FOREIGN KEY (manager_id) REFERENCES task_service.users(id);

--
-- Name: task_comments fk6n4f8xnvwdkbjci078pqdn1w1; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_comments
    ADD CONSTRAINT fk6n4f8xnvwdkbjci078pqdn1w1 FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: tasks fk76tiq4q248au3u79a8nkexoth; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT fk76tiq4q248au3u79a8nkexoth FOREIGN KEY (parent_task_id) REFERENCES task_service.tasks(id);

--
-- Name: project_history fk7p433kc2lowhbmhl2rkroqksl; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.project_history
    ADD CONSTRAINT fk7p433kc2lowhbmhl2rkroqksl FOREIGN KEY (changed_by) REFERENCES task_service.users(id);

--
-- Name: tasks fk7xndk7y2uk29wdu734xyutbqe; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT fk7xndk7y2uk29wdu734xyutbqe FOREIGN KEY (task_status_id) REFERENCES task_service.task_statuses(id);

--
-- Name: job_title_role_mappings fk8f3qyj4xaqji8jg3es4gi2f3h; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.job_title_role_mappings
    ADD CONSTRAINT fk8f3qyj4xaqji8jg3es4gi2f3h FOREIGN KEY (job_title_id) REFERENCES task_service.job_titles(id);

--
-- Name: task_attachments fk8g8b4opj0xx4ptu66b8gxkkdo; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_attachments
    ADD CONSTRAINT fk8g8b4opj0xx4ptu66b8gxkkdo FOREIGN KEY (uploaded_by) REFERENCES task_service.users(id);

--
-- Name: teams fk8rwj1snfq6jjnj7wbgbcjgxi3; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.teams
    ADD CONSTRAINT fk8rwj1snfq6jjnj7wbgbcjgxi3 FOREIGN KEY (parent_team_id) REFERENCES task_service.teams(id);

--
-- Name: task_comments fk9517viwn2geh1gpivj6l9y64u; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_comments
    ADD CONSTRAINT fk9517viwn2geh1gpivj6l9y64u FOREIGN KEY (task_id) REFERENCES task_service.tasks(id);

--
-- Name: teams fk957exwbsdbxyv2kir751ejid7; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.teams
    ADD CONSTRAINT fk957exwbsdbxyv2kir751ejid7 FOREIGN KEY (manager_id) REFERENCES task_service.users(id);

--
-- Name: job_title_role_mappings fk_jtrm_user; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.job_title_role_mappings
    ADD CONSTRAINT fk_jtrm_user FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: tasks fkbvjdsa9y725wovwlq4sjhodyk; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT fkbvjdsa9y725wovwlq4sjhodyk FOREIGN KEY (reporter_id) REFERENCES task_service.users(id);

--
-- Name: team_capacity fkd2c3a1hvnkpgc45f8jw8uf246; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_capacity
    ADD CONSTRAINT fkd2c3a1hvnkpgc45f8jw8uf246 FOREIGN KEY (team_id) REFERENCES task_service.teams(id);

--
-- Name: role_change_requests fkd9dhgu58ehgshnnwc9dhj6d76; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.role_change_requests
    ADD CONSTRAINT fkd9dhgu58ehgshnnwc9dhj6d76 FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: team_members fkee8x7x5026imwmma9kndkxs36; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_members
    ADD CONSTRAINT fkee8x7x5026imwmma9kndkxs36 FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: tasks fkekr1dgiqktpyoip3qmp6lxsit; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT fkekr1dgiqktpyoip3qmp6lxsit FOREIGN KEY (assignee_id) REFERENCES task_service.users(id);

--
-- Name: absences fkfj92n9rpcqkq6lqm3aqoak9wv; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.absences
    ADD CONSTRAINT fkfj92n9rpcqkq6lqm3aqoak9wv FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: user_capacity fkiui2fw3yo39om4lodhvrvp18t; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.user_capacity
    ADD CONSTRAINT fkiui2fw3yo39om4lodhvrvp18t FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: task_history fkjqraeud129avhcva579fhioj3; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_history
    ADD CONSTRAINT fkjqraeud129avhcva579fhioj3 FOREIGN KEY (task_id) REFERENCES task_service.tasks(id);

--
-- Name: tasks fkkb5gyrxp5x7p5inusg3ykdugt; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT fkkb5gyrxp5x7p5inusg3ykdugt FOREIGN KEY (task_type_id) REFERENCES task_service.task_types(id);

--
-- Name: projects fkmqih0928bq6r3gbuh47giq8w; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.projects
    ADD CONSTRAINT fkmqih0928bq6r3gbuh47giq8w FOREIGN KEY (team_id) REFERENCES task_service.teams(id);

--
-- Name: projects fkmueqy6cpcwpfl8gnnag4idjt9; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.projects
    ADD CONSTRAINT fkmueqy6cpcwpfl8gnnag4idjt9 FOREIGN KEY (owner_id) REFERENCES task_service.users(id);

--
-- Name: task_types fkn99dior6arm0u1107rb7j3uih; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_types
    ADD CONSTRAINT fkn99dior6arm0u1107rb7j3uih FOREIGN KEY (subtask_type_id) REFERENCES task_service.task_types(id);

--
-- Name: project_history fknqot7iut415wq4vg43uo4s0qp; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.project_history
    ADD CONSTRAINT fknqot7iut415wq4vg43uo4s0qp FOREIGN KEY (project_id) REFERENCES task_service.projects(id);

--
-- Name: time_logs fkoun22vhbya8md711x7gbqv5j6; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.time_logs
    ADD CONSTRAINT fkoun22vhbya8md711x7gbqv5j6 FOREIGN KEY (task_id) REFERENCES task_service.tasks(id);

--
-- Name: time_logs fkpa0td7bk535jt0143oslckvxj; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.time_logs
    ADD CONSTRAINT fkpa0td7bk535jt0143oslckvxj FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- Name: job_title_role_mappings fkqjob3ph5fihjyj2xn871srecn; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.job_title_role_mappings
    ADD CONSTRAINT fkqjob3ph5fihjyj2xn871srecn FOREIGN KEY (role_id) REFERENCES task_service.user_roles(id);

--
-- Name: tasks fksfhn82y57i3k9uxww1s007acc; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.tasks
    ADD CONSTRAINT fksfhn82y57i3k9uxww1s007acc FOREIGN KEY (project_id) REFERENCES task_service.projects(id);

--
-- Name: team_members fktgca08el3ofisywcf11f0f76t; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.team_members
    ADD CONSTRAINT fktgca08el3ofisywcf11f0f76t FOREIGN KEY (team_id) REFERENCES task_service.teams(id);

--
-- Name: task_history fkvws0ackho5jjqad4sfy8e0tk; Type: FK CONSTRAINT; Schema: task_service; Owner: postgres
--

ALTER TABLE ONLY task_service.task_history
    ADD CONSTRAINT fkvws0ackho5jjqad4sfy8e0tk FOREIGN KEY (user_id) REFERENCES task_service.users(id);

--
-- PostgreSQL database dump complete
--
