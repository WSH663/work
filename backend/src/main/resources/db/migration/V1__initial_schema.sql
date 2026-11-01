CREATE TABLE users (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) UNIQUE,
 password_hash VARCHAR(100),
 open_id VARCHAR(128) UNIQUE,
 nickname VARCHAR(60) NOT NULL,
 phone VARCHAR(20),
 role VARCHAR(20) NOT NULL DEFAULT 'USER',
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 invite_code VARCHAR(20) NOT NULL UNIQUE,
 referrer_id BIGINT,
 available_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 frozen_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (referrer_id) REFERENCES users(id)
);
CREATE TABLE auth_tokens (
 token_hash VARCHAR(64) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE passengers (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 name VARCHAR(60) NOT NULL,
 phone VARCHAR(20) NOT NULL,
 FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE addresses (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 label VARCHAR(50) NOT NULL,
 address VARCHAR(255) NOT NULL,
 location VARCHAR(60) NOT NULL,
 FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE vehicles (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 code VARCHAR(20) NOT NULL UNIQUE,
 seats INT NOT NULL,
 description VARCHAR(1000) NOT NULL,
 base_fare DECIMAL(12,2) NOT NULL,
 per_km DECIMAL(12,2) NOT NULL,
 included_km DECIMAL(8,2) NOT NULL DEFAULT 0,
 charter_hourly DECIMAL(12,2) NOT NULL,
 minimum_hours DECIMAL(8,2) NOT NULL DEFAULT 4,
 surcharge DECIMAL(12,2) NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE content (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 kind VARCHAR(20) NOT NULL,
 title VARCHAR(120) NOT NULL,
 body VARCHAR(3000) NOT NULL DEFAULT '',
 media_url VARCHAR(1000) NOT NULL DEFAULT '',
 media_type VARCHAR(20) NOT NULL DEFAULT 'TEXT',
 link_url VARCHAR(1000) NOT NULL DEFAULT '',
 sort_order INT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 skip_seconds INT NOT NULL DEFAULT 3
);
CREATE TABLE settings (
 setting_key VARCHAR(80) PRIMARY KEY,
 setting_value VARCHAR(3000) NOT NULL
);
CREATE TABLE orders (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_no VARCHAR(40) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL,
 vehicle_id BIGINT NOT NULL,
 vehicle_name VARCHAR(80) NOT NULL,
 type VARCHAR(20) NOT NULL,
 status VARCHAR(30) NOT NULL,
 previous_status VARCHAR(30),
 origin_name VARCHAR(255) NOT NULL,
 destination_name VARCHAR(255) NOT NULL,
 origin VARCHAR(60) NOT NULL,
 destination VARCHAR(60) NOT NULL,
 appointment_at TIMESTAMP NOT NULL,
 passenger_name VARCHAR(60) NOT NULL,
 passenger_phone VARCHAR(20) NOT NULL,
 distance_km DECIMAL(10,2) NOT NULL DEFAULT 0,
 hours DECIMAL(8,2),
 amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 driver_name VARCHAR(60),
 driver_phone VARCHAR(20),
 plate_number VARCHAR(20),
 remark VARCHAR(1000) NOT NULL DEFAULT '',
 itinerary VARCHAR(2000) NOT NULL DEFAULT '',
 quote_remark VARCHAR(1000),
 commission_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES users(id),
 FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);
CREATE INDEX idx_orders_user ON orders(user_id, created_at);
CREATE INDEX idx_orders_status ON orders(status, created_at);
CREATE TABLE order_logs (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(50) NOT NULL,
 content VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE payments (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT NOT NULL,
 order_no VARCHAR(40) NOT NULL,
 type VARCHAR(20) NOT NULL,
 mode VARCHAR(20) NOT NULL,
 amount DECIMAL(12,2) NOT NULL,
 status VARCHAR(20) NOT NULL,
 transaction_no VARCHAR(80) NOT NULL UNIQUE,
 reason VARCHAR(1000) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE commissions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 referred_user_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL UNIQUE,
 order_no VARCHAR(40) NOT NULL,
 amount DECIMAL(12,2) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'SETTLED',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES users(id),
 FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE withdrawals (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 amount DECIMAL(12,2) NOT NULL,
 account_name VARCHAR(60) NOT NULL,
 account_no VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 remark VARCHAR(1000),
 payment_reference VARCHAR(120),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_withdrawals_user ON withdrawals(user_id);
CREATE TABLE messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 title VARCHAR(120) NOT NULL,
 content VARCHAR(1000) NOT NULL,
 read_flag BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE audit_logs (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 actor_name VARCHAR(80) NOT NULL,
 action VARCHAR(100) NOT NULL,
 target VARCHAR(120) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO vehicles(name,code,seats,description,base_fare,per_km,included_km,charter_hourly,minimum_hours,surcharge,enabled) VALUES
 ('A型 · 舒适无障碍车','A',4,'适合日常就医、探亲与城市出行。轮椅坡道及固定装置，预约时请说明轮椅尺寸与协助需求。',35,4,3,90,4,0,TRUE),
 ('B型 · 宽享无障碍车','B',6,'更宽敞的车内空间，可陪同家属出行。提供轮椅辅助上下车，具体车型由客服确认。',50,5,3,120,4,0,TRUE);
INSERT INTO settings(setting_key,setting_value) VALUES
 ('platformName','"预约出行"'),('servicePhone','""'),
 ('notice','"预约出行示例服务，请先配置服务区域、客服与运营信息。"'),
 ('serviceCity','"示例城市"'),('serviceArea','"示例服务区域，请在后台配置实际服务范围"'),
 ('advanceMinutes','60'),('maxAdvanceDays','30'),('commissionRate','0.05'),('withdrawalMinimum','10'),('cancelHours','2');
INSERT INTO content(kind,title,body,media_type,sort_order) VALUES
 ('BANNER','每一次出发，都值得被认真照顾','预约出行 · 示例服务','TEXT',1),
 ('NOTICE','预约服务提醒','请提前说明轮椅尺寸、同行人数以及上下车辅助需求，客服将与您确认服务安排。','TEXT',1),
 ('SERVICE','预约用车服务说明','费用由车型、里程及附加服务计算；包车订单由客服人工报价，确认后支付。开发模式支付仅供联调。','TEXT',1),
 ('AD','让出行更从容','为轮椅使用者、长者及行动不便人士提供预约出行服务。','TEXT',1);
