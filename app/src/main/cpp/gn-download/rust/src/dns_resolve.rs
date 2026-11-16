//! Резолв имён с таймаутом и кэшем — общий для всех HTTP-клиентов движка.
//!
//! На Android libc-шный `getaddrinfo` ходит в netd через unix-сокет (`dnsproxyd`) и своего
//! таймаута не имеет: без ответа поток висит вечно (`tokio-rt-worker` в `unix_stream_data_wait`).
//! Резолвер reqwest по умолчанию делает этот вызов в blocking-пуле tokio, поэтому зависший
//! резолв ещё и съедает поток пула, а `Runtime::drop` ждёт такие потоки — прогон перестаёт
//! реагировать на отмену. Здесь резолв идёт в отдельном потоке и ждётся с дедлайном: запрос
//! падает и переезжает на другой сервер, а прогон не встаёт намертво.
//!
//! Успешный результат кэшируется на время жизни резолвера (один прогон). На хост может быть
//! только ОДИН поток резолва: зависший остаётся единственным (libc такой вызов не отменить),
//! а следующие запросы получают ошибку по дедлайну, а не плодят потоки. Ошибка, которую резолв
//! вернул быстро, повторяется через [`FAILURE_BACKOFF`].

use std::collections::HashMap;
use std::io;
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Дедлайн одного резолва. CDN-хост отвечает за миллисекунды; 5 с хватает медленному
/// мобильному DNS и не съедает терпение сторожевого таймера.
pub const RESOLVE_TIMEOUT: Duration = Duration::from_secs(5);
/// Сколько держим быструю ошибку резолва, прежде чем повторить.
const FAILURE_BACKOFF: Duration = Duration::from_secs(30);

/// Настоящий резолв (`getaddrinfo` внутри).
pub fn lookup_host(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    (host, port).to_socket_addrs().map(|addrs| addrs.collect())
}

fn timeout_error(host: &str, timeout: Duration) -> io::Error {
    io::Error::new(
        io::ErrorKind::TimedOut,
        format!("dns lookup for '{host}' timed out after {timeout:?}"),
    )
}

/// Запускает `lookup` отдельным потоком и ждёт ответ не дольше `timeout`. Поток резолва
/// намеренно не join-ится по таймауту: зависший `getaddrinfo` не отменить, а ожидание его —
/// ровно тот вечный висяк, от которого этот модуль и защищает.
pub fn lookup_with_timeout<F>(timeout: Duration, lookup: F) -> io::Result<Vec<SocketAddr>>
where
    F: FnOnce() -> io::Result<Vec<SocketAddr>> + Send + 'static,
{
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::Builder::new()
        .name("dns-lookup".into())
        .spawn(move || {
            let _ = tx.send(lookup());
        })
        .map_err(|err| io::Error::other(format!("dns lookup thread: {err}")))?;
    match rx.recv_timeout(timeout) {
        Ok(result) => result,
        Err(std::sync::mpsc::RecvTimeoutError::Timeout) => Err(timeout_error("", timeout)),
        Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
            Err(io::Error::other("dns lookup thread disappeared"))
        }
    }
}

type LookupFn = dyn Fn(&str, u16) -> io::Result<Vec<SocketAddr>> + Send + Sync;

/// Резолвер для reqwest: кэш на прогон и дедлайн на каждый резолв.
#[derive(Clone)]
pub struct TimedResolver {
    inner: Arc<Inner>,
}

struct Inner {
    timeout: Duration,
    lookup: Arc<LookupFn>,
    cache: Mutex<HashMap<String, Entry>>,
}

/// Ответ потока резолва, общий для всех, кто ждёт этот хост.
type Slot = Arc<Mutex<Option<Arc<io::Result<Vec<SocketAddr>>>>>>;

enum Entry {
    Ready(Vec<SocketAddr>),
    /// Резолв этого хоста уже идёт. Остальные запросы ждут его ответ, а не плодят потоки:
    /// зависший `getaddrinfo` не отменить, число таких потоков должно быть ограничено.
    Waiting(Slot),
    /// Резолв быстро вернул ошибку (DNS ответил). Повтор через [`FAILURE_BACKOFF`].
    Failed(Instant),
}

impl Default for TimedResolver {
    fn default() -> Self {
        Self::new()
    }
}

impl TimedResolver {
    pub fn new() -> Self {
        Self::with_lookup(RESOLVE_TIMEOUT, Arc::new(lookup_host))
    }

    /// Конструктор для тестов/альтернативного резолва.
    pub fn with_lookup(timeout: Duration, lookup: Arc<LookupFn>) -> Self {
        Self {
            inner: Arc::new(Inner {
                timeout,
                lookup,
                cache: Mutex::new(HashMap::new()),
            }),
        }
    }

    /// Резолвит `host` в адреса с портом 0 — порт из URI подставит коннектор reqwest.
    pub async fn resolve_host(&self, host: &str) -> io::Result<Vec<SocketAddr>> {
        let (slot, fresh) = {
            let mut cache = self.inner.cache.lock().expect("dns cache poisoned");
            match cache.get(host) {
                Some(Entry::Ready(addrs)) => return Ok(addrs.clone()),
                Some(Entry::Failed(at)) if at.elapsed() < FAILURE_BACKOFF => {
                    return Err(io::Error::other(format!("dns lookup for '{host}' failed")));
                }
                Some(Entry::Waiting(slot)) => (Arc::clone(slot), false),
                _ => {
                    let slot: Slot = Arc::new(Mutex::new(None));
                    cache.insert(host.to_string(), Entry::Waiting(Arc::clone(&slot)));
                    (slot, true)
                }
            }
        };
        if fresh {
            self.spawn_lookup(host, Arc::clone(&slot));
        }
        wait_for_lookup(&slot, host, self.inner.timeout).await
    }

    /// Запускает единственный поток резолва для `host`; он публикует результат и в слот, и в
    /// кэш, чтобы уже отвалившийся по таймауту ждатель не оставил хост навсегда в `Waiting`.
    fn spawn_lookup(&self, host: &str, slot: Slot) {
        let inner = Arc::clone(&self.inner);
        let host = host.to_string();
        let lookup_host = host.clone();
        let slot_in_thread = Arc::clone(&slot);
        let spawned = std::thread::Builder::new()
            .name("dns-lookup".into())
            .spawn(move || {
                let result = Arc::new((inner.lookup)(&lookup_host, 0).and_then(|addrs| {
                    if addrs.is_empty() {
                        Err(io::Error::other(format!(
                            "dns lookup for '{lookup_host}' returned no address"
                        )))
                    } else {
                        Ok(addrs)
                    }
                }));
                {
                    let mut cache = inner.cache.lock().expect("dns cache poisoned");
                    match &*result {
                        Ok(addrs) => {
                            cache.insert(lookup_host, Entry::Ready(addrs.clone()));
                        }
                        Err(_) => {
                            cache.insert(lookup_host, Entry::Failed(Instant::now()));
                        }
                    }
                }
                *slot_in_thread.lock().expect("dns slot poisoned") = Some(Arc::clone(&result));
            });
        if let Err(err) = spawned {
            // Поток не стартовал — фейлим хост, иначе ждатели провисят до таймаута.
            let mut cache = self.inner.cache.lock().expect("dns cache poisoned");
            cache.insert(host, Entry::Failed(Instant::now()));
            *slot.lock().expect("dns slot poisoned") = Some(Arc::new(Err(io::Error::other(
                format!("dns lookup thread: {err}"),
            ))));
        }
    }
}

/// Ждёт (с дедлайном) ответ потока резолва по хосту.
async fn wait_for_lookup(slot: &Slot, host: &str, timeout: Duration) -> io::Result<Vec<SocketAddr>> {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(result) = slot.lock().expect("dns slot poisoned").as_ref() {
            return match &**result {
                Ok(addrs) => Ok(addrs.clone()),
                Err(err) => Err(io::Error::new(err.kind(), err.to_string())),
            };
        }
        if Instant::now() >= deadline {
            return Err(timeout_error(host, timeout));
        }
        // Короткий опрос: ответ приходит из обычного потока, ожидание остаётся отменяемым.
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

impl reqwest::dns::Resolve for TimedResolver {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        let this = self.clone();
        Box::pin(async move {
            let addrs: reqwest::dns::Addrs =
                Box::new(this.resolve_host(name.as_str()).await?.into_iter());
            Ok(addrs)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::future::join;

    #[test]
    fn a_hung_lookup_fails_by_deadline_instead_of_blocking() {
        let started = Instant::now();
        let err = lookup_with_timeout(Duration::from_millis(50), || {
            std::thread::sleep(Duration::from_secs(5));
            Ok(vec![])
        })
        .expect_err("a hung lookup must not succeed");
        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert!(
            started.elapsed() < Duration::from_secs(1),
            "{:?}",
            started.elapsed()
        );
    }

    #[test]
    fn a_hung_resolve_does_not_stall_the_runtime_or_the_cache_peer() {
        let resolver = Arc::new(TimedResolver::with_lookup(
            Duration::from_millis(100),
            Arc::new(|host: &str, _port: u16| {
                if host == "slow.invalid" {
                    std::thread::sleep(Duration::from_secs(5));
                }
                Ok(vec![SocketAddr::from(([127, 0, 0, 1], 0))])
            }),
        ));
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .build()
            .expect("runtime");
        let started = Instant::now();
        let (slow, fast) = rt.block_on(join(
            resolver.resolve_host("slow.invalid"),
            resolver.resolve_host("fast.invalid"),
        ));
        assert!(slow.is_err(), "the hung lookup must surface an error");
        assert_eq!(fast.expect("fast lookup")[0].ip().to_string(), "127.0.0.1");
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "{:?}",
            started.elapsed()
        );
    }

    #[test]
    fn concurrent_resolves_of_a_hung_host_share_one_lookup_thread() {
        let calls = Arc::new(std::sync::atomic::AtomicU32::new(0));
        let counter = Arc::clone(&calls);
        let resolver = Arc::new(TimedResolver::with_lookup(
            Duration::from_millis(80),
            Arc::new(move |_host: &str, _port: u16| {
                counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                std::thread::sleep(Duration::from_secs(5));
                Ok(vec![SocketAddr::from(([127, 0, 0, 3], 0))])
            }),
        ));
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .build()
            .expect("runtime");
        rt.block_on(async {
            let a = resolver.resolve_host("hung.invalid");
            let b = resolver.resolve_host("hung.invalid");
            let c = resolver.resolve_host("hung.invalid");
            let (ra, rb, rc) = futures_util::future::join3(a, b, c).await;
            assert!(ra.is_err() && rb.is_err() && rc.is_err());
        });
        assert_eq!(
            calls.load(std::sync::atomic::Ordering::Relaxed),
            1,
            "a hung host must not strand one thread per request"
        );
    }

    #[test]
    fn a_successful_lookup_is_cached_for_the_run() {
        let calls = Arc::new(std::sync::atomic::AtomicU32::new(0));
        let counter = Arc::clone(&calls);
        let resolver = TimedResolver::with_lookup(
            Duration::from_millis(100),
            Arc::new(move |_host: &str, _port: u16| {
                counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                Ok(vec![SocketAddr::from(([127, 0, 0, 2], 0))])
            }),
        );
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .build()
            .expect("runtime");
        rt.block_on(async {
            for _ in 0..3 {
                resolver.resolve_host("cdn.example").await.expect("lookup");
            }
        });
        assert_eq!(calls.load(std::sync::atomic::Ordering::Relaxed), 1);
    }
}
