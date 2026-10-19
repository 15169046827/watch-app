# Builder dependency security

## Scope and release boundary

This remediation concerns the Windows Builder's Melo inference runtime and target-specific
Tauri dependency graph. It is not a fresh whole-product audit or an Android/device validation.
Source changes do not repair previously distributed portable archives or running processes.
A replacement portable candidate needs its own clean-source build, actual runtime verification,
upgrade/resume/cache/rollback acceptance and artifact identity checks before publication.

## Transformers and Melo compatibility

The old recipe pinned Transformers 4.27.4 in Python 3.9. The reviewed GitHub advisory ranges
extend through versions below 5.10.0; bumping only the Python requirement file cannot work
in that interpreter or prove speech synthesis still works.

The new recipe uses Python 3.11, Transformers 5.10.1, PyTorch 2.13.0, NumPy 1.26.4,
librosa 0.11.0 and setuptools 83.0.0. The standalone runtime guard checks the actual installed
Python/Transformers/PyTorch versions before model import and before package promotion.
CPU wheel versions must also be audited under their base PyPI version: an audit tool's
`not found on PyPI` message for a `+cpu` wheel is an unknown, not a clean security result.
PyTorch 2.8.0 is not an acceptable fallback: additional model-loading and tensor-operation
advisories were found when auditing its base version.

Melo source is fixed to official commit `209145371cff8fc3bd60d7be902ea69cbdb7965a`.
Its source ZIP SHA-256 is
`3EDF1B2AB60E777691AB2EC5FBA837E278F60761C296BC5D753DA5AB8AB5754E`.
The recipe verifies that archive and reapplies it before the documented inference-only
dependency metadata and soundfile adaptations. Those adaptations retain the upstream MIT
license and do not change model weights or the synthesis algorithm. An arbitrary older
torchaudio wheel must not be paired with a newer torch despite its ABI/version constraint.

The supported worker accepts only its packaged `ZH` model. Novel text is input to the
normalizer/tokenizer, not a model path, configuration, training checkpoint, optimizer,
remote-code repository or output-template name. PyTorch's restricted weight loader is forced
even if a contrary environment variable is inherited. Upstream sentence printing is disabled.

### Advisory reachability

| Advisory group | Supported inference path |
| --- | --- |
| GHSA-3863-2447-669p / v68g-wm8c-6x7j / 37q5-v5qm-c9v8, older unsafe deserialization | No TensorFlow/training checkpoint API; fixed official inference model IDs and restricted torch loading. The vulnerable library is nevertheless replaced. |
| GHSA-wrfc-pvp9-mr9g / hxxf-235m-72v3 / qxrp-vhvm-j765 | MaskFormer, Trax and MobileViTV2 are not used; the affected library version is still removed. |
| GHSA-29pf-2h5f-8g72 / fgcw-684q-jj6r / 69w3-r845-3855 | No arbitrary CausalLM/LightGlue config, kernels or Trainer RNG restore; upgraded beyond the advisory fixed versions. |
| GHSA-xrqw-3rrv-vx5w | No save_pretrained/chat-template save operation; upgraded beyond 5.10.0. |
| GHSA-4w7r-h757-3r74 / rcv9-qm8p-9p6j / 59p9-h35m-wg4g / 9356-575x-2w9m / 37mw-44qp-f5jm / jjph-296x-mrcr / q2wp-rjmx-x6x9 / qq3j-4f4f-9583 / fpwr-67px-3qhx / 6rvg-6v2m-4j46 | Affected optimizer, Whisper normalizer, Marian, TF conversion, Donut, dynamic-code parser, docstring testing, GPT-NeoX-Japanese and Nougat paths are not the fixed BERT tokenizer/feature path. The whole library is upgraded, not patched selectively. |
| GHSA-phhr-52qp-3mj4 / 282v-666c-3fvg | No remote media URL or affected historical temporary-checkpoint path in supported inference; upgrade retained. |

This is a call-path assessment, not a claim that every model/API in the old library was safe.
Model/cache tampering by another process with the same user privileges remains a local
trust boundary; users must not import arbitrary checkpoints or model Python code.

## glib remains an upstream inventory alert

[GHSA-wrw7-89jp-8q8g](https://github.com/advisories/GHSA-wrw7-89jp-8q8g) concerns
undefined behavior in `VariantStrIter` and is fixed in glib 0.20.0. The locked dependency
chain Tauri 2.11.5 → GTK 0.18.2 → glib `^0.18` cannot resolve 0.20.0 independently.
The lockfile includes non-Windows dependency inventory; removing those records manually
would not fix the dependency constraint and is not an acceptable remediation.

The supported `x86_64-pc-windows-msvc` graph contains no glib, GTK or WebKitGTK.
The build script rejects non-Windows targets while the upstream issue remains unresolved.
`builder/tools/check_windows_dependencies.py` checks the resolved Windows graph, fails if
the root graph is missing and rejects GTK-family packages; its tests include negative fixtures.
No Dependabot dismissal, advisory ignore, lockfile fabrication or unsupported upstream fork
is used. The alert remains open and must be re-evaluated when Tauri/GTK constraints change
or before adding another platform.

## Reproducible checks

Run from the product directory with a separate compatible interpreter:

```powershell
& '<python.exe>' -B -m unittest discover -s builder/melo -p 'test_*.py'
& '<python.exe>' -B -m unittest discover -s builder/tools -p 'test_windows_dependencies.py'
& '<python.exe>' -B builder/tools/check_windows_dependencies.py --offline
& '.tools/melo-next/python.exe' -B builder/melo/runtime_security.py
& '<python.exe>' -B builder/melo/verify_runtime.py --python .tools/melo-next/python.exe
```

The last check synthesizes self-written public Chinese, mixed-language/numeric and Unicode
samples through the actual worker with socket connections blocked. It checks PCM format,
non-silence and duration, not subjective listening quality. Private books, real task state and
external devices are never inputs to these tests. Known transitive advisory exceptions or
scanner gaps must be recorded separately; this document does not claim a zero-alert inventory.

`:builder:test` depends on the stdlib-only `testDependencySecurity` task, so the existing
Gradle CI command also executes the policy fixtures. `X3_BUILDER_TEST_PYTHON` may select
a local interpreter; the default is `python`. Real synthesis and complete graph checks
remain separate acceptance checks, not claims made by those small unit fixtures.

Portable preview packaging accepts `-CandidateName security-test` to isolate its app,
staging, rollback and ZIP/checksum paths from the active default preview. Candidate names
are restricted to lowercase ASCII letters, digits and hyphens (48 characters maximum),
excluding reserved `next`/`previous` suffixes. Run `builder/tools/test-preview-paths.ps1`
and `builder/test-portable-upgrade.ps1 -RollbackOnly` before packaging changes ship.
`-EdgePackagesPath` selects a separately validated Edge dependency root containing
`site-packages`; use wheels matching the selected Melo Python ABI rather than copying
old compiled extensions. Neither option changes the installed shortcut or publishes a release.
Install `builder/edge/requirements.txt` with the selected Python into a **fresh** isolated
`site-packages` target (`pip install --target <target> --only-binary=:all: --no-compile -r ...`).
The reviewed candidate pins aiohttp 3.14.5 and multidict 6.9.1 to include current advisory
fixes; run a fresh dependency audit, compiled-parser import check, updater tests and actual
public-sentence synthesis before using that directory for packaging. Do not upgrade the
active user's dependency tree in place.
Both Windows packagers fail closed on stale Edge versions or missing compiled Python 3.11
extensions before copying dependencies. This is a baseline/ABI check, not a replacement
for current advisory scans or real synthesis. Formal signing requirements remain unchanged.
Edge and Sherpa use the portable Python stored under `.tools/melo`; consequently
`-WithoutMelo` is valid only together with `-WithoutAdditionalVoices`. Both packagers run a
shared policy fixture and fail before building if asked to create an image that keeps those
providers but omits their interpreter.

The copied portable cache omits `pytorch_model.bin` only where its known multilingual
BERT snapshot also contains nonempty `model.safetensors`. Source caches, other models,
Melo checkpoints and lone legacy files are never removed. Packagers reject reparse points
and require real network-blocked sample synthesis from the copied runtime after pruning,
before archive creation. Run `builder/tools/test-melo-package.ps1` for scoped/idempotent
and corrupt-pair fixtures. Do not execute other acceptance tools inside a directory while
it is being archived: subprocess imports may generate bytecode that the content gate rejects.
librosa/Numba initialization can also create **empty** `__pycache__` directories even with
bytecode disabled. After copied-runtime verification, packagers remove only empty cache
directories (non-recursively) and reject other forbidden files/directories before compression,
reporting relative examples. Nonempty caches are not silently deleted or shipped.
Setup removes only the generated pinned-source extraction directory before expanding the
SHA-256-verified archive, after rejecting reparse points and active Builder/Melo processes.
This prevents stale or locally added files from surviving `Expand-Archive -Force` overlays.

The final environment scan also reports [GHSA-8mgp-746c-j5xp](https://github.com/advisories/GHSA-8mgp-746c-j5xp)
in NLTK 3.10.3, with no published fix at review time. Its affected training/model-path
import/export methods are not exposed by the Builder: `g2p_en` uses fixed dictionaries,
its packaged numeric checkpoint and `pos_tag`. Book text cannot choose NLTK model paths,
training targets or saved model filenames. Retain the inventory alert and re-evaluate
before adding model-management/training APIs or when a fixed NLTK release is available.
The local-source Melo package has no PyPI audit entry; its pinned source, SHA-256 and
small installation adaptations are reviewed directly rather than silently skipped.
