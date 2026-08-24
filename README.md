# Apache Arrow C++ for z/OS

[Apache Arrow](https://github.com/apache/arrow) C++ 25.0.1, built as a static
library.

> [!WARNING]
> **This does not build yet.** It configures cleanly and 60 source files
> compile before two blockers stop it, both recorded below with their exact
> errors. The port exists so the configure recipe — which took several rounds
> to find, and none of whose settings are guessable — is written down and
> reproducible.

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

## How far it gets

| stage | result |
| --- | --- |
| CMake configure | ✅ 380 status lines, `Configuring done` / `Generating done` |
| compile | 60 files, then 2 errors |

## What is blocking it

### 1. No thread-local storage

```
src/arrow/vendored/datetime/tz.cpp:3984:5:
error: thread-local storage is not supported for the current target
```

This compiler supports no TLS of any kind — `thread_local`, `_Thread_local`
and `__thread` are all rejected. It is the same wall the greenlet port hit, and
the same fix applies: a `pthread_key_create` destructor gives the semantics
that matter (created on first use per thread, destroyed at thread exit).

The file is Howard Hinnant's date library, vendored into Arrow at
`cpp/src/arrow/vendored/datetime/`. Worth checking first whether the timezone
database can simply be switched off for a minimal build, since nothing in the
core needs it.

### 2. `struct tm` has no `tm_gmtoff`

```
src/arrow/util/value_parsing.h:837:41:
error: no member named 'tm_gmtoff' in 'tm'
```

`tm_gmtoff` is a BSD/glibc extension. It does not exist on z/OS **under any
feature-test macro** — checked with `_ALL_SOURCE`, `_XOPEN_SOURCE_EXTENDED` and
both together. So this needs a real patch computing the UTC offset another way,
not a define.

## The configure recipe

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

1. Decide whether the vendored timezone database can be switched off for a core
   build. If not, apply the pthread-key shim from `greenletport`.
2. Patch `value_parsing.h` to compute the UTC offset without `tm_gmtoff`.
3. Get the core building, then **run Arrow's own test suite before touching
   pyarrow**. The dependencies are tractable; the real unknown is how many
   places assume little-endian outside the paths s390x CI exercises, and only
   the test suite will answer that.
