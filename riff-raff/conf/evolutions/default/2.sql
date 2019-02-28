# RiffRaff indices

# --- !Ups

CREATE INDEX apiKey_application  ON apiKey ((content ->> 'application'));

CREATE INDEX deploy_startTime ON deploy ((content ->> 'startTime'));
CREATE INDEX deploy_status ON deploy ((content ->> 'status'));
CREATE INDEX deploy_projectName ON deploy ((content -> 'parameters' ->> 'projectName'));

CREATE INDEX deployLog_time ON deployLog ((content ->> 'time'));
CREATE INDEX deployLog_deploy ON deployLog ((content ->> 'deploy'));

# --- !Downs

DROP INDEX apiKey_application;
DROP INDEX deploy_startTime;
DROP INDEX deploy_status;
DROP INDEX deploy_projectName;
DROP INDEX deployLog_time;
DROP INDEX deployLog_deploy;
