# RiffRaff schema

# --- !Ups

CREATE TABLE apiKeys (id CHAR(32) PRIMARY KEY, content jsonb NOT NULL);

CREATE TABLE auth (email VARCHAR(100) PRIMARY KEY, content jsonb NOT NULL);

CREATE TABLE deploy (id UUID PRIMARY KEY, content jsonb NOT NULL);

CREATE TABLE deployLogs (id UUID PRIMARY KEY, content jsonb NOT NULL);

# --- !Downs

DROP TABLE apiKeys;

DROP TABLE auth;

DROP TABLE deploy;

DROP TABLE deployLogs;