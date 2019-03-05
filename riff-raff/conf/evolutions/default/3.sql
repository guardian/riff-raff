# Index required efficient for queries on startTime timestamp field

# --- !Ups

CREATE OR REPLACE FUNCTION f_starttime_timestamp(text)
  RETURNS timestamptz AS
$$SELECT to_timestamp($1, 'YYYY-MM-DDTHH:MI:SS.MS')$$  -- adapt to your needs
  LANGUAGE sql IMMUTABLE;

CREATE INDEX deploy_startTime_timestamp ON deploy (f_starttime_timestamp(content ->> 'startTime'));


# --- !Downs

DROP INDEX deploy_startTime_timestamp;


