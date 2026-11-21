create table if not exists spotify_users (
  spotify_user_id varchar(255) primary key,
  display_name varchar(255),
  access_token_ciphertext text,
  refresh_token_ciphertext text,
  access_token_expires_at timestamptz,
  connection_status varchar(32) not null default 'CONNECTED',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
