-- !Ups

DROP VIEW service_catalogue.riffraff_authorized_users;
DROP TABLE auth;

-- !Downs

CREATE TABLE auth (email VARCHAR(100) PRIMARY KEY, content jsonb NOT NULL);
CREATE VIEW service_catalogue.riffraff_authorized_users AS SELECT * FROM public.auth;