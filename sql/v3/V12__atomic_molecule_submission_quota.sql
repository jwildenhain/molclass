USE molclass_v3;

-- Atomic counter for anonymous ad-hoc molecule submissions. The API increments
-- and reads this row in the same transaction, preventing concurrent requests
-- from all passing a COUNT-before-INSERT quota check.
CREATE TABLE molecule_submission_quota (
    quota_date DATE NOT NULL,
    client_ip VARBINARY(16) NOT NULL,
    submission_count INT UNSIGNED NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (quota_date, client_ip)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve today's accepted anonymous count when upgrading a database where
-- V11 has already been serving traffic.
INSERT INTO molecule_submission_quota
    (quota_date, client_ip, submission_count, updated_at)
SELECT UTC_DATE(), client_ip, COUNT(*), MAX(created_at)
FROM molecule_submission
WHERE api_key_id IS NULL
  AND created_at >= UTC_DATE()
  AND created_at < UTC_DATE() + INTERVAL 1 DAY
GROUP BY client_ip;
