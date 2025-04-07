**Project Idea**

We will be working with the H2 open-source database as the foundation for our project. H2 is widely adopted as a datastore in many of the world's most popular Java-based, JDBC-driven applications — for instance, Spring uses H2 as its default embedded database engine. However, H2 currently suffers from significant performance limitations in high-concurrency environments. The database uses a single-writer model with table-level locks, which severely restricts write throughput. Its static cache implementation cannot adapt to changing workload patterns. H2 fails to utilize multi-core processors effectively for query execution. The MVCC implementation creates bottlenecks by holding shared locks during reads and exclusive locks during writes.

To address these issues, we propose a three-tier caching system and a parallel query engine. The caching system includes a thread-local cache, a shared application cache, and an adaptive disk cache. It adjusts memory allocation based on real-time query patterns. The parallel query engine splits complex queries into smaller parts processed by multiple threads, utilizing all CPU cores efficiently. This solution improves concurrency handling without requiring application changes and maintains full SQL compatibility.


**Proposed Solution**

Our solution implements a new caching system with three distinct tiers. The thread-local cache stores data used frequently by individual query threads. The shared application cache maintains data accessed by multiple threads. The adaptive disk cache manages efficient page flushing to disk.
The system tracks query patterns and access statistics in real-time. It measures read/write ratios and cache hit rates across all tiers. Based on these metrics, it automatically adjusts memory allocation between cache tiers every few seconds. This adapts the database to changing workloads without manual configuration.
The parallel query engine analyzes SELECT statements to identify parallelization opportunities. It focuses on complex queries that access large tables. The engine splits compatible queries into independent fragments. Each fragment processes a portion of the data using worker threads from a dedicated thread pool. Results from all fragments are combined into a single result set. This approach utilizes all available CPU cores efficiently.
By addressing both caching and query execution limitations, our solution significantly improves H2's concurrency handling. It eliminates bottlenecks in both read and write operations while maintaining full SQL compatibility. The implementation requires no application changes and can be enabled through simple configuration settings.

**Proposed Evaluation**

To assess the effectiveness of our proposed caching and parallel query execution enhancements in H2, we will conduct a series of benchmark tests.  For parallel execution, we will use the TPC-H benchmark, an industry-standard workload featuring large tables and complex joins, ideal for measuring query speedup and CPU utilization. To assess our caching system, we will run read-heavy, write-heavy, and mixed workloads, analyzing cache hit rates, memory adaptation, and overall query performance.

Our primary evaluation metrics include speedup ratio (baseline vs. optimized execution time), cache hit rate (how effectively we reduce I/O), and throughput (queries executed per second across different workloads). We expect our system to achieve higher throughput in high-concurrency environments, more efficient memory utilization through adaptive tiered caching, and improved query latency for read heavy and mixed workloads.
