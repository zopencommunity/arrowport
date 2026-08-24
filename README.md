# Apache Arrow C++ for z/OS

[Apache Arrow](https://github.com/apache/arrow) C++ 25.0.1, built as a static
library.

**The core library builds and works.** `libarrow.a` comes out at about 40 MB,
and a program linked against it produces correct results on this byte order:

```
arrow 25.0.1, len=5, type=int64
values ok: YES (sum=15000000105, expect 15000000105)
strings: [z/OS] [arrow]
```

**Compression is not finished.** That result is from a core build without the
codecs. Turning them on gets through configure and 34 files, then stops on
`posix_memalign` being undeclared in `memory_pool.cc` — see
[Compression status](#compression-status).

The string array matters as much as the integer one — offsets are where
endianness actually bites.

Six patches were needed. Every one is a platform difference rather than an
Arrow bug, and none of them touches Arrow's logic.

## Why this is separate from pyarrow

pyarrow cannot build Arrow C++. Its `CMakeLists.txt` does:

```cmake
find_package(Arrow REQUIRED)
```

`PYARROW_BUNDLE_ARROW_CPP` (default OFF) only controls whether the libraries
are *copied into the wheel*, not whether they are built. So the C++ library has
to exist first regardless.

Beyond that: Arrow C++ is interpreter-independent while pyarrow builds a wheel
per ABI, so bundling would rebuild a very large C++ project three times per
release for nothing. This is the same shape as `lapack`/`blis` → `scipy`.

## The patches

| file | why |
| --- | --- |
| `util/endian.h` | includes `<endian.h>` for anything that is not Apple, FreeBSD, Solaris, AIX or QNX. z/OS has no such header — one line adding `__MVS__` beside `_AIX`. |
| `util/value_parsing.h` | uses `tm.tm_gmtoff`, which does not exist on z/OS **under any feature-test macro**. Arrow already guards this with `#if !defined(_WIN32) && !defined(_AIX)` — the same platforms that lack it — so the fix is adding `__MVS__` to Arrow's own existing list. |
| `util/io_util.cc` | `pthread_kill((pthread_t)thread_id, ...)`. Arrow's comment says pthread_t "can be a pointer *or* integer type"; on z/OS it is a **struct**, so no cast works. `GetThreadId()` builds the id by memcpy-ing a `std::thread::id`, so memcpy back is the exact inverse. |
| `util/thread_pool.cc` | `thread_local ThreadPool* current_thread_pool_`. Replaced with zoslib's `__tlssim`. |
| `vendored/datetime/tz.cpp` | `thread_local recursion_limiter rc{10}`. Also `__tlssim`, but held as a pointer — see below. |
| `vendored/fast_float/bigint.h` | z/OS `<strings.h>` defines **`rindex` as a macro**, and fast_float has a method of that name. One `#undef`. |

### Thread-local storage

This compiler supports none — `thread_local`, `_Thread_local` and `__thread`
are all rejected, and no `-mzos-target` level changes that (`zosv3r1` is
accepted and does move `__TARGET_LIB__` to `0x43010000`; TLS is a
code-generation capability the target does not implement, not a library-level
feature).

Arrow has 109 `thread_local` occurrences across 14 files, but a core build
reaches **two** — 94 are in Acero, 11 in Flight/ODBC, 2 in tests, and 3 are
comments.

Both are fixed with zoslib's `__tlssim<T>` from `<zos-tls.h>`, which simulates
thread-local storage over a pthread key. That is the right tool rather than
`-Dthread_local=`, which some ports use: defining the keyword away compiles,
but every thread then shares one variable. Measured, two threads incrementing a
counter 1000 times each:

| approach | main thread sees | correct? |
| --- | --- | --- |
| `-Dthread_local=` | 2000 | no |
| `__tlssim` | 0 | yes |

That distinction is load-bearing here: `current_thread_pool_` is how a pool
recognises its own worker threads.

Two traps worth knowing if you use `__tlssim` elsewhere:

* **It copy-constructs its initial value.** `recursion_limiter` deletes its copy
  constructor and has no default one, so it cannot be held directly; the patch
  holds `__tlssim<recursion_limiter*>` and allocates on first use per thread.
* **Include `<zos-tls.h>` at file scope.** It declares `extern "C"` entry
  points, and including it inside a namespace namespaces them — which links as
  `_ZN5arrow8internal21__tlsvaranchor_createEm UNRESOLVED`, long after it
  compiled cleanly.

## Compression status

`snappy`, `zstd`, `lz4`, `bzip2`, `zlib` and `utf8proc` are all ported and wired
up in the buildenv, and the configure now completes with them. Three
integration problems were solved getting there, each of which reports something
other than its cause:

| symptom | cause |
| --- | --- |
| zoslib's `time.h`: `unknown type name 'time_t'` | `-DCMAKE_CXX_FLAGS` **replaces** what CMake seeds from `$CXXFLAGS`, discarding zoslib's `-isystem`, its `ZOSLIB_OVERRIDE_CLIB` defines and its symbolfixes force-include. Use `ZOPEN_EXTRA_CXXFLAGS` instead. |
| `IMPORTED_LOCATION not set for BZip2::BZip2` | `FindBZip2` builds the imported target from `BZIP2_LIBRARY_RELEASE`, not the plural `BZIP2_LIBRARIES`. |
| `IMPORTED_IMPLIB not set for zstd::libzstd_shared` | Arrow was selecting the *shared* target from each dependency's CMake config; an imported SHARED target here wants a side-deck as its import library, which static-only ports do not ship. `ARROW_DEPENDENCY_USE_SHARED=OFF`. |

What remains is:

```
memory_pool.cc:318: error: use of undeclared identifier 'posix_memalign'
```

Not yet explained. `posix_memalign` **is** declared on this platform under every
combination tried — no feature macros, `_XOPEN_SOURCE=600`, `_ALL_SOURCE`,
`_XOPEN_SOURCE_EXTENDED`, `_POSIX_C_SOURCE`, and also with zoslib's
`-isystem` path and `ZOSLIB_OVERRIDE_CLIB` defines applied. zoslib's own
`stdlib.h` declares it. `memory_pool.cc` includes `<cstdlib>`.

So the difference is something in the full build's include set rather than a
missing declaration, and the obvious candidates are ruled out. The core build
that produced the working library above did **not** carry zoslib's overrides,
which is the main thing that differs.

## The configure recipe## The configure recipe

Every one of these was found by hitting the failure it prevents. The failures
are recorded because none of them names its own cause.

| setting | without it |
| --- | --- |
| `ARROW_CPU_FLAG=s390x` | `Unknown system processor` — CMake reports the machine type (`8561`), not an architecture Arrow knows. Arrow *has* an s390x path and the detection is guarded by `if(NOT DEFINED ARROW_CPU_FLAG)`, so naming it skips detection without a patch. |
| `CMAKE_CXX_STANDARD=20` | `Cannot set a CMAKE_CXX_STANDARD smaller than 20`. Arrow 25 requires C++20; verified working here — concepts, `std::span`, `__cplusplus 202002`. |
| `CMAKE_RANLIB=/bin/true` | `Could not find EP_CMAKE_RANLIB using the following names: :` — there is no `ranlib`; `ar` maintains the index itself, as on AIX. |
| `-faligned-allocation` | every file that `new`s an over-aligned type fails, starting with `memory_pool.cc`. |
| `xsimd_SOURCE=SYSTEM` | Arrow downloads its own xsimd, which fails here — and the ported one is the one that works. |
| the xsimd force-include | see below. |

### The xsimd flags cannot be flags

Arrow requires xsimd and uses batches in its kernels. On z/OS xsimd only works
through its emulated backend, and the settings that select it contain angle
brackets:

```
-DXSIMD_DEFAULT_ARCH=xsimd::emulated<128>
```

By the time CMake has passed that through a shell, `<128>` has been read as a
redirection, and Arrow's compiler probe fails before compiling anything of its
own:

```
bad file descriptor "128"
```

`xsimdport` therefore delivers them as a force-included header and exports it
through `zopen_append_to_env`, so listing `xsimd` as a dependency is enough.

## What is deliberately off

Core only: no Compute, CSV, JSON, Filesystem, Parquet, Dataset, Acero, Flight,
S3 or GCS, and no jemalloc or mimalloc. That is all pyarrow strictly requires,
and it keeps the target small while the platform problems are being worked out.
Compression is off for the same reason, though `snappy`, `zstd`, `lz4` and
`brotli` are all ported and can be switched on once the core builds.

`re2` and `rapidjson` are left to Arrow's bundling: re2 pulls in Abseil, which
is a substantial port of its own, and neither is needed by a core build.

## Endianness

Better than expected, and the reason this is worth pursuing at all. Arrow's
big-endian support is first-class rather than an afterthought:

```c
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#  define ARROW_LITTLE_ENDIAN 1
#else
#  define ARROW_LITTLE_ENDIAN 0
#endif
```

IBM contributed s390x support upstream and it is exercised in Arrow's CI, so
the byte-order paths are not untested code. z/OS predefines everything that
logic needs.

The one portability bug found so far is in that same header and is patched
here: `endian.h` includes `<endian.h>` for anything that is not Apple, FreeBSD,
Solaris, AIX or QNX, and z/OS has no such header. One line, adding `__MVS__`
beside `_AIX`.

## Next steps

1. Resolve the `posix_memalign` declaration failure. Reproduce it by compiling
   `memory_pool.cc` alone with the port's exact flags, then bisect the include
   set — `arrow/memory_pool_internal.h` is the first include and the least
   examined.
2. Then Arrow's own test suite, which is currently parked (see the buildenv for
   why and for exactly what to change). Everything established so far is that
   the library builds and that two array types round-trip; that is not the same
   as every byte-order path being right on a big-endian machine.
3. Then pyarrow, which additionally needs `ARROW_COMPUTE` and probably
   `ARROW_PARQUET` — and therefore thrift.
