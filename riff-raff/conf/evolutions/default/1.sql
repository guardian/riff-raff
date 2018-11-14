# RiffRaff schema

# --- !Ups

CREATE TABLE apiKey (id CHAR(32) PRIMARY KEY, content jsonb NOT NULL);

CREATE TABLE auth (email VARCHAR(100) PRIMARY KEY, content jsonb NOT NULL);

CREATE TABLE deploy (id UUID PRIMARY KEY, content jsonb NOT NULL);

CREATE TABLE deployLog (id UUID PRIMARY KEY, content jsonb NOT NULL);

# --- !Downs

DROP TABLE apiKey;

DROP TABLE auth;

DROP TABLE deploy;

DROP TABLE deployLog;