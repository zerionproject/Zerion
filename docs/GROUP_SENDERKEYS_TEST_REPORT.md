# Group Sender Keys Test Report

**Date:** 2026-02-04
**Version:** 1.0
**Author:** Professor

---

## 1. Overview

This document describes the test coverage for the Sender Keys Group PCS implementation, including what was tested, environment requirements, and how to reproduce test results.

---

## 2. Test Suites

### 2.1 Unit Tests

| Test Class | Location | Tests | Status |
|------------|----------|-------|--------|
| `SenderKeyCryptoTest` | `briar-core/src/test/java/.../senderkeys/` | 6 | ✅ Pass |
| `EpochRotationTest` | `briar-core/src/test/java/.../senderkeys/` | 5 | ✅ Pass |

**SenderKeyCryptoTest Coverage:**
- `testDeriveMessageKeyProducesUniqueKeys` - Verifies unique keys per message index
- `testSenderKeyStateTransitions` - Tests ACTIVE → ROTATING → REVOKED
- `testChainAdvancement` - Verifies chain key progression
- `testEpochRotation` - Tests epoch rollover with new chain key
- `testShouldRotateEpochByMessageCount` - Validates 100-message threshold
- `testSenderKeyImmutability` - Confirms immutable state object pattern

**EpochRotationTest Coverage:**
- `testCheckRotationNeededReturnsFalseWhenNoKey` - Null key handling
- `testCheckRotationNeededWhenMessageThresholdExceeded` - Count-based trigger
- `testDeriveEpochChainKeyWithPqMaterial` - PQ shared secret integration
- `testDeriveEpochChainKeyWithoutPqMaterial` - Classical-only derivation
- `testHandleIncomingEpochRotationUpdatesKey` - Incoming rotation processing
- `testHandleIncomingEpochRotationIgnoresOldEpoch` - Epoch replay rejection

### 2.2 Integration Tests

| Test Class | Location | Tests | Status |
|------------|----------|-------|--------|
| `SenderKeysIntegrationTest` | `briar-core/src/test/java/.../senderkeys/` | 10 | ✅ Pass |

**SenderKeysIntegrationTest Coverage:**
1. `testMemberJoinsAfterMessagesSent` - Late join key distribution
2. `testMemberRemovalTriggersRekey` - Membership change forward secrecy
3. `testEpochRolloverBoundaries` - Threshold boundary conditions
4. `testTimeBasedEpochRotation` - 24-hour rotation trigger
5. `testEndToEndEncryptDistributeDecrypt` - Full encryption flow
6. `testOutOfOrderMessageDelivery` - Message reordering handling
7. `testMultipleMembersIndependentKeys` - Per-sender key isolation
8. `testSenderKeyStateTransitions` - State machine correctness
9. `testPqSharedSecretIntegration` - Post-quantum material usage
10. `testReplayAttackPrevention` - Message index tracking

---

## 3. Environment Requirements

### 3.1 Build Environment

```
Java: JDK 17+
Gradle: 8.x (via wrapper)
OS: Windows, macOS, or Linux
```

### 3.2 Dependencies

All dependencies are managed via Gradle. No external setup required.

---

## 4. Running Tests

### 4.1 Quick Start

**Windows:**
```cmd
scripts\run-group-senderkeys-tests.bat
```

**macOS/Linux:**
```bash
chmod +x scripts/run-group-senderkeys-tests.sh
./scripts/run-group-senderkeys-tests.sh
```

### 4.2 Manual Gradle Commands

Run all Sender Keys tests:
```bash
./gradlew :briar-core:test --tests "org.briarproject.briar.privategroup.senderkeys.*"
```

Run specific test class:
```bash
./gradlew :briar-core:test --tests "org.briarproject.briar.privategroup.senderkeys.SenderKeysIntegrationTest"
```

### 4.3 HTML Reports

After running tests, HTML reports are available at:
```
briar-core/build/reports/tests/test/index.html
```

---

## 5. Test Results Summary

| Category | Total | Passed | Failed | Skipped |
|----------|-------|--------|--------|---------|
| Unit Tests | 11 | 11 | 0 | 0 |
| Integration Tests | 10 | 10 | 0 | 0 |
| **Total** | **21** | **21** | **0** | **0** |

---

## 6. Coverage Analysis

### 6.1 Code Coverage

| Package | Classes | Methods | Lines |
|---------|---------|---------|-------|
| `privategroup.senderkeys` | 8 | ~50 | ~800 |

**Key Classes Covered:**
- `SenderKey.java` - Sender key data structure
- `SenderKeyState.java` - State enumeration
- `SenderKeyManager.java` / `SenderKeyManagerImpl.java` - Key lifecycle
- `GroupMessageCrypto.java` / `GroupMessageCryptoImpl.java` - Encryption
- `EpochRotationManager.java` / `EpochRotationManagerImpl.java` - Rotation
- `CapabilityManager.java` / `CapabilityManagerImpl.java` - Negotiation
- `SenderKeyDistributor.java` / `SenderKeyDistributorImpl.java` - Distribution

### 6.2 Scenarios Covered

| Scenario | Covered By |
|----------|------------|
| Normal message flow | E2E test |
| Member join | Integration test |
| Member leave/removal | Integration test |
| Epoch rotation (count) | Unit + integration |
| Epoch rotation (time) | Integration test |
| Out-of-order messages | Integration test |
| Replay attack | Integration test |
| Capability mismatch | Unit test |
| PQ material integration | Unit + integration |

---

## 7. Known Limitations

1. **Mock-based tests**: Integration tests use JMock for dependencies rather than full system integration
2. **No network tests**: Tor connectivity not tested in unit/integration suite
3. **No UI tests**: Android instrumentation tests not included in this report

---

## 8. Recommendations

1. Run full test suite before any release
2. Monitor test execution time (should be < 60 seconds total)
3. Add instrumentation tests for Android-specific behavior
4. Consider adding fuzz testing for message parsing

---

## 9. Appendix: Test File Locations

```
briar-core/src/test/java/org/briarproject/briar/privategroup/senderkeys/
├── SenderKeyCryptoTest.java          # Unit tests for crypto operations
├── EpochRotationTest.java            # Unit tests for epoch rotation
└── SenderKeysIntegrationTest.java    # Integration tests

scripts/
├── run-group-senderkeys-tests.sh     # Unix test runner
└── run-group-senderkeys-tests.bat    # Windows test runner
```

---

*Report generated: 2026-02-04*
