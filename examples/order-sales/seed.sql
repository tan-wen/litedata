-- Local demo_source only. Six synthetic orders; no production data.
CREATE TABLE IF NOT EXISTS demo_source.sales_demo_orders (
    id BIGINT NOT NULL PRIMARY KEY,
    customer_name VARCHAR(128) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX ix_sales_demo_updated (updated_at)
);
INSERT INTO demo_source.sales_demo_orders(id,customer_name,amount,updated_at) VALUES
    (1,'华东商贸',120.00,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE),
    (2,'华东商贸',80.00,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE),
    (3,'华南设备',250.00,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE),
    (4,'华北零售',300.00,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE),
    (5,'华南设备',150.00,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE),
    (6,'华北零售',90.00,UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE)
ON DUPLICATE KEY UPDATE customer_name=VALUES(customer_name),amount=VALUES(amount),updated_at=VALUES(updated_at);
