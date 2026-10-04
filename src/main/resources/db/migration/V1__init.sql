CREATE TABLE shows (
                       id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       name            TEXT NOT NULL,
                       price_paise     BIGINT NOT NULL CHECK (price_paise >= 0),
                       per_user_limit  INT NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
                       total_seats     INT NOT NULL CHECK (total_seats > 0),
                       created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
                              id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              show_id       UUID NOT NULL REFERENCES shows(id),
                              user_id       TEXT NOT NULL,
                              seats         TEXT[] NOT NULL,
                              amount_paise  BIGINT NOT NULL CHECK (amount_paise >= 0),
                              status        TEXT NOT NULL CHECK (status IN ('confirmed','cancelled')),
                              created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
                       show_id         UUID NOT NULL REFERENCES shows(id),
                       seat_no         TEXT NOT NULL,
                       status          TEXT NOT NULL DEFAULT 'available'
                           CHECK (status IN ('available','held','confirmed')),
                       reservation_id  UUID REFERENCES reservations(id),
                       user_id         TEXT,
                       PRIMARY KEY (show_id, seat_no),
                       CHECK ((status = 'available') = (reservation_id IS NULL))
);
CREATE INDEX idx_seats_show_status ON seats(show_id, status);

CREATE TABLE idempotency_keys (
                                  user_id         TEXT NOT NULL,
                                  idem_key        TEXT NOT NULL,
                                  show_id         UUID NOT NULL,
                                  request_hash    TEXT NOT NULL,
                                  reservation_id  UUID REFERENCES reservations(id),
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  PRIMARY KEY (user_id, idem_key)
);

CREATE TABLE user_show_counts (
                                  show_id  UUID NOT NULL REFERENCES shows(id),
                                  user_id  TEXT NOT NULL,
                                  held     INT NOT NULL DEFAULT 0 CHECK (held >= 0),
                                  PRIMARY KEY (show_id, user_id)
);