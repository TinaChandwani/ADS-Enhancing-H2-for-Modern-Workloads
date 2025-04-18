-- Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
-- and the EPL 1.0 (https://h2database.com/html/license.html).
-- Initial Developer: H2 Group
--

-- Truncate least significant digits because implementations returns slightly
-- different results depending on Java version
select degrees(null) vn, truncate(degrees(1), 10) v1, truncate(degrees(1.1), 10) v2,
    truncate(degrees(-1.1), 10) v3, truncate(degrees(1.9), 10) v4,
    truncate(degrees(-1.9), 10) v5;
> VN   V1           V2            V3             V4             V5
> ---- ------------ ------------- -------------- -------------- ---------------
> null 57.295779513 63.0253574643 -63.0253574643 108.8619810748 -108.8619810748
> rows: 1
