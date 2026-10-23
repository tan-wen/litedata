CREATE TABLE IF NOT EXISTS orders (
  id BIGINT NOT NULL,
  customer_name VARCHAR(128) NOT NULL,
  amount DECIMAL(18, 2) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_orders_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO orders (id, customer_name, amount, updated_at) VALUES
  (1, 'Ada', 120.50, '2026-01-01 00:00:00.000000'),
  (2, 'Linus', 88.00, '2026-01-01 00:00:01.000000')
ON DUPLICATE KEY UPDATE customer_name = VALUES(customer_name), amount = VALUES(amount), updated_at = VALUES(updated_at);

REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'demo_reader'@'%';
GRANT SELECT ON demo_source.* TO 'demo_reader'@'%';
