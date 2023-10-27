-- Read only user for sourcing service-catalogue data

-- !Ups

-- We cant check-in a password for this user in VCS... for reasons that I hope are fairly obvious!
-- Therefore after deployment a password must be manually set using the below query
-- ALTER USER service_catalogue PASSWORD 'new-password'
CREATE USER service_catalogue;

-- We dont necessarily need/want all data from riff-raff or want the same table names.
-- The cloudquery postgres plugin doesn't give many options for filtering for specific tables or renaming.
-- So create a view for only the tables we want and rename to be more user friendly in service-catalogue.
CREATE SCHEMA AUTHORIZATION service_catalogue;
ALTER USER service_catalogue SET search_path TO service_catalogue;

CREATE VIEW service_catalogue.riffraff_deploys AS SELECT * FROM public.deploy;
CREATE VIEW service_catalogue.riffraff_deploy_logs AS SELECT * FROM public.deploylog;
CREATE VIEW service_catalogue.riffraff_authorized_users AS SELECT * FROM public.auth;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM service_catalogue;
GRANT SELECT ON ALL TABLES IN SCHEMA service_catalogue TO service_catalogue;

-- !Downs

DROP VIEW service_catalogue.riffraff_deploys;
DROP VIEW service_catalogue.riffraff_deploy_logs;
DROP VIEW service_catalogue.riffraff_authorized_users;
DROP SCHEMA service_catalogue;
DROP USER service_catalogue;