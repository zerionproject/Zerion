
#include <jni.h>
#include <string>
#include <cstdint>
#include <chrono>
#include <thread>
#include <vector>
#include <map>
#include <set>
#include <mutex>
#include <utility>
#include <limits>
#include "wallet2_api.h"

#define private public
#include "wallet.h"

#include "pending_transaction.h"
#undef private

using Monero::WalletManagerFactory;
using Monero::WalletManager;
using Monero::Wallet;
using Monero::PendingTransaction;
using Monero::TransactionHistory;
using Monero::TransactionInfo;

namespace {

static const jlong NLONG_ERR = std::numeric_limits<jlong>::min();

jstring emptyString(JNIEnv *env) { return env->NewStringUTF(""); }

bool clearPending(JNIEnv *env) {
    if (env->ExceptionCheck()) { env->ExceptionClear(); return true; }
    return false;
}

jstring toJString(JNIEnv *env, const std::string &s) {
    jstring j = env->NewStringUTF(s.c_str());
    if (clearPending(env)) return nullptr;
    return j;
}

std::string toStd(JNIEnv *env, jstring s) {
    if (s == nullptr) return std::string();
    const char *c = env->GetStringUTFChars(s, nullptr);
    if (c == nullptr) { clearPending(env); return std::string(); }
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string bytesToStd(JNIEnv *env, jbyteArray a) {
    if (a == nullptr) return std::string();
    jsize n = env->GetArrayLength(a);
    if (n <= 0) return std::string();
    jbyte *p = env->GetByteArrayElements(a, nullptr);

    if (p == nullptr) { clearPending(env); return std::string(); }
    std::string out(reinterpret_cast<char *>(p), (size_t) n);
    for (jsize i = 0; i < n; i++) p[i] = 0;
    env->ReleaseByteArrayElements(a, p, JNI_ABORT);
    return out;
}

void wipe(std::string &s) {
    if (!s.empty()) {
        volatile char *v = const_cast<volatile char *>(s.data());
        for (size_t i = 0; i < s.size(); i++) v[i] = 0;
    }
    s.clear();
}

jbyteArray toByteArray(JNIEnv *env, std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    if (a == nullptr) { clearPending(env); wipe(s); return nullptr; }
    if (!s.empty()) {
        env->SetByteArrayRegion(a, 0, (jsize) s.size(),
                reinterpret_cast<const jbyte *>(s.data()));
        if (clearPending(env)) { wipe(s); return nullptr; }
    }
    wipe(s);
    return a;
}

WalletManager *wm() {

    static bool silenced = [] {
        WalletManagerFactory::setLogLevel(WalletManagerFactory::LogLevel_Silent);
        WalletManagerFactory::setLogCategories("");
        return true;
    }();
    (void) silenced;
    return WalletManagerFactory::getWalletManager();
}

enum HandleKind { KIND_WALLET = 1, KIND_TX = 2 };
struct HandleEntry { void *ptr; HandleKind kind; jlong parent; };

typedef std::vector<std::pair<jlong, PendingTransaction *>> TxOrphans;
std::mutex g_regMu;
std::map<jlong, HandleEntry> g_reg;
jlong g_nextId = 1;

jlong regAdd(void *ptr, HandleKind kind, jlong parent) {
    if (!ptr) return 0;
    std::lock_guard<std::mutex> lk(g_regMu);
    jlong id = g_nextId++;
    g_reg[id] = HandleEntry{ptr, kind, parent};
    return id;
}

Wallet *regWallet(jlong id) {
    std::lock_guard<std::mutex> lk(g_regMu);
    auto it = g_reg.find(id);
    if (it == g_reg.end() || it->second.kind != KIND_WALLET) return nullptr;
    return reinterpret_cast<Wallet *>(it->second.ptr);
}

PendingTransaction *regTxWithParent(jlong id, Wallet **parentOut) {
    std::lock_guard<std::mutex> lk(g_regMu);
    auto it = g_reg.find(id);
    if (it == g_reg.end() || it->second.kind != KIND_TX) return nullptr;
    auto pit = g_reg.find(it->second.parent);
    if (pit == g_reg.end() || pit->second.kind != KIND_WALLET) return nullptr;
    if (parentOut) *parentOut = reinterpret_cast<Wallet *>(pit->second.ptr);
    return reinterpret_cast<PendingTransaction *>(it->second.ptr);
}

void regRemove(jlong id) {
    std::lock_guard<std::mutex> lk(g_regMu);
    g_reg.erase(id);
}

PendingTransaction *regTakeTx(jlong id, Wallet **parentOut) {
    std::lock_guard<std::mutex> lk(g_regMu);
    auto it = g_reg.find(id);
    if (it == g_reg.end() || it->second.kind != KIND_TX) return nullptr;
    auto pit = g_reg.find(it->second.parent);
    Wallet *parent = (pit != g_reg.end() && pit->second.kind == KIND_WALLET)
            ? reinterpret_cast<Wallet *>(pit->second.ptr) : nullptr;
    PendingTransaction *t =
            reinterpret_cast<PendingTransaction *>(it->second.ptr);
    g_reg.erase(it);
    if (parentOut) *parentOut = parent;
    return t;
}

Wallet *regTakeWallet(jlong id, TxOrphans &orphans) {
    std::lock_guard<std::mutex> lk(g_regMu);
    auto it = g_reg.find(id);
    if (it == g_reg.end() || it->second.kind != KIND_WALLET) return nullptr;
    Wallet *w = reinterpret_cast<Wallet *>(it->second.ptr);
    for (auto &e : g_reg) {
        if (e.second.kind == KIND_TX && e.second.parent == id) {
            orphans.push_back(std::make_pair(e.first,
                    reinterpret_cast<PendingTransaction *>(e.second.ptr)));
        }
    }
    for (auto &o : orphans) g_reg.erase(o.first);
    g_reg.erase(id);
    return w;
}

Wallet *asWallet(jlong h) { return regWallet(h); }
PendingTransaction *asTx(jlong h) { return regTxWithParent(h, nullptr); }

}

#define JNI_GUARD(FALLBACK, BODY) \
    try { BODY } catch (...) { return FALLBACK; }
#define JNI_GUARD_VOID(BODY) \
    try { BODY } catch (...) { }

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nCreate(
        JNIEnv *env, jclass, jstring path, jbyteArray password, jstring language) {
    JNI_GUARD(0, {
        std::string pw = bytesToStd(env, password);
        Wallet *w = wm()->createWallet(toStd(env, path), pw,
                                       toStd(env, language), Monero::MAINNET);
        wipe(pw);
        return regAdd(w, KIND_WALLET, 0);
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nRestore(
        JNIEnv *env, jclass, jstring path, jbyteArray password, jbyteArray seed,
        jlong restoreHeight, jbyteArray seedOffset) {
    JNI_GUARD(0, {
        std::string pw = bytesToStd(env, password);
        std::string sd = bytesToStd(env, seed);
        std::string off = bytesToStd(env, seedOffset);
        Wallet *w = wm()->recoveryWallet(toStd(env, path), pw, sd,
                                         Monero::MAINNET,
                                         (uint64_t) restoreHeight, 1, off);
        wipe(pw); wipe(sd); wipe(off);
        return regAdd(w, KIND_WALLET, 0);
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nOpen(
        JNIEnv *env, jclass, jstring path, jbyteArray password) {
    JNI_GUARD(0, {
        std::string pw = bytesToStd(env, password);
        Wallet *w = wm()->openWallet(toStd(env, path), pw, Monero::MAINNET);
        wipe(pw);
        return regAdd(w, KIND_WALLET, 0);
    })
}

JNIEXPORT jint JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nStatus(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(-1, {
        Wallet *w = asWallet(h);
        return w ? (jint) w->status() : (jint) -1;
    })
}

JNIEXPORT jstring JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nErrorString(
        JNIEnv *env, jclass, jlong h) {
    JNI_GUARD(emptyString(env), {
        Wallet *w = asWallet(h);
        return toJString(env, w ? w->errorString() : std::string("null wallet"));
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nStore(
        JNIEnv *env, jclass, jlong h, jstring path) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        return w && w->store(toStd(env, path)) ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nClose(
        JNIEnv *, jclass, jlong h, jboolean store) {
    JNI_GUARD(JNI_FALSE, {

        TxOrphans orphans;
        Wallet *w = regTakeWallet(h, orphans);
        if (!w) return JNI_FALSE;
        for (auto &o : orphans) {
            if (o.second) w->disposeTransaction(o.second);
        }
        return wm()->closeWallet(w, store == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jbyteArray JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nSeed(
        JNIEnv *env, jclass, jlong h, jbyteArray seedOffset) {
    JNI_GUARD(env->NewByteArray(0), {
        Wallet *w = asWallet(h);
        std::string off = bytesToStd(env, seedOffset);
        std::string s = w ? w->seed(off) : std::string();
        wipe(off);
        return toByteArray(env, s);
    })
}

JNIEXPORT jstring JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nAddress(
        JNIEnv *env, jclass, jlong h, jlong account, jlong subaddr) {
    JNI_GUARD(emptyString(env), {
        Wallet *w = asWallet(h);
        return toJString(env, w ? w->address((uint32_t) account, (uint32_t) subaddr)
                                : std::string());
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nAddSubaddress(
        JNIEnv *env, jclass, jlong h, jlong account, jstring label) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) w->addSubaddress((uint32_t) account, toStd(env, label));
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nNumSubaddresses(
        JNIEnv *, jclass, jlong h, jlong account) {
    JNI_GUARD(NLONG_ERR, {
        Wallet *w = asWallet(h);
        return w ? (jlong) w->numSubaddresses((uint32_t) account) : NLONG_ERR;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nValidateAddress(
        JNIEnv *env, jclass, jstring address) {
    JNI_GUARD(JNI_FALSE, {
        return Monero::Wallet::addressValid(toStd(env, address), Monero::MAINNET)
               ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nInit(
        JNIEnv *env, jclass, jlong h, jstring daemonAddress, jstring proxyAddress,
        jboolean trusted) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        if (!w) return JNI_FALSE;
        w->setTrustedDaemon(trusted == JNI_TRUE);
        return w->init(toStd(env, daemonAddress), 0, "", "", false, false,
                       toStd(env, proxyAddress)) ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nSetRefreshFromHeight(
        JNIEnv *, jclass, jlong h, jlong height) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) w->setRefreshFromBlockHeight((uint64_t) height);
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nSetRecoveringFromSeed(
        JNIEnv *, jclass, jlong h, jboolean recovering) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) w->setRecoveringFromSeed(recovering == JNI_TRUE);
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nGetRefreshFromHeight(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(NLONG_ERR, {
        Wallet *w = asWallet(h);
        if (!w) return NLONG_ERR;
        return (jlong) w->getRefreshFromBlockHeight();
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nRefresh(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        return w && w->refresh() ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nBlockchainHeight(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(NLONG_ERR, {
        Wallet *w = asWallet(h);
        return w ? (jlong) w->blockChainHeight() : NLONG_ERR;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nDaemonHeight(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(NLONG_ERR, {
        Wallet *w = asWallet(h);
        return w ? (jlong) w->daemonBlockChainHeight() : NLONG_ERR;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nSynchronized(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        return w && w->synchronized() ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nBalance(
        JNIEnv *, jclass, jlong h, jlong account) {
    JNI_GUARD(NLONG_ERR, {
        Wallet *w = asWallet(h);
        return w ? (jlong) w->balance((uint32_t) account) : NLONG_ERR;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nUnlockedBalance(
        JNIEnv *, jclass, jlong h, jlong account) {
    JNI_GUARD(NLONG_ERR, {
        Wallet *w = asWallet(h);
        return w ? (jlong) w->unlockedBalance((uint32_t) account) : NLONG_ERR;
    })
}

JNIEXPORT jstring JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nHistory(
        JNIEnv *env, jclass, jlong h) {

    JNI_GUARD(nullptr, {
        Wallet *w = asWallet(h);
        if (!w) return (jstring) nullptr;
        TransactionHistory *th = w->history();
        if (!th) return (jstring) nullptr;
        th->refresh();
        std::string out;
        for (TransactionInfo *ti : th->getAll()) {
            if (!ti) continue;
            out += ti->hash();                                        out += ",";
            out += std::to_string(ti->direction());                   out += ",";
            out += std::to_string((unsigned long long) ti->amount()); out += ",";
            out += std::to_string((unsigned long long) ti->fee());    out += ",";
            out += std::to_string((unsigned long long) ti->blockHeight()); out += ",";
            out += std::to_string((long long) ti->timestamp());       out += ",";
            out += std::to_string((unsigned long long) ti->confirmations()); out += ",";
            out += std::to_string((unsigned long long) ti->unlockTime()); out += ",";
            out += (ti->isPending() ? "1" : "0");                     out += ",";
            out += (ti->isFailed() ? "1" : "0");                      out += "\n";
        }
        return toJString(env, out);
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nStop(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) w->stop();
    })
}

JNIEXPORT jint JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nConnected(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(-1, {
        Wallet *w = asWallet(h);
        return w ? (jint) w->connected() : (jint) -1;
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nSetAutoRefreshInterval(
        JNIEnv *, jclass, jlong h, jint millis) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) w->setAutoRefreshInterval((int) millis);
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nStartRefresh(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) w->startRefresh();
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nPauseRefresh(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) w->pauseRefresh();
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nStopRefreshThread(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD_VOID({
        Wallet *w = asWallet(h);
        if (w) static_cast<Monero::WalletImpl *>(w)->stopRefresh();
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nPrepare(
        JNIEnv *env, jclass, jlong h, jstring address, jlong amount,
        jint priority, jlong account) {
    JNI_GUARD(0, {
        Wallet *w = asWallet(h);
        if (!w) return 0;
        PendingTransaction *tx = w->createTransaction(
                toStd(env, address), "", (uint64_t) amount, 0,
                static_cast<PendingTransaction::Priority>(priority),
                (uint32_t) account);
        return regAdd(tx, KIND_TX, h);
    })
}

JNIEXPORT jint JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxStatus(
        JNIEnv *, jclass, jlong tx) {
    JNI_GUARD(-1, {
        PendingTransaction *t = asTx(tx);
        return t ? (jint) t->status() : (jint) -1;
    })
}

JNIEXPORT jstring JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxError(
        JNIEnv *env, jclass, jlong tx) {
    JNI_GUARD(emptyString(env), {
        PendingTransaction *t = asTx(tx);
        return toJString(env, t ? t->errorString() : std::string("null tx"));
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxFee(
        JNIEnv *, jclass, jlong tx) {
    JNI_GUARD(NLONG_ERR, {
        PendingTransaction *t = asTx(tx);
        return t ? (jlong) t->fee() : NLONG_ERR;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxAmount(
        JNIEnv *, jclass, jlong tx) {
    JNI_GUARD(NLONG_ERR, {
        PendingTransaction *t = asTx(tx);
        return t ? (jlong) t->amount() : NLONG_ERR;
    })
}

JNIEXPORT jstring JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxId(
        JNIEnv *env, jclass, jlong tx) {
    JNI_GUARD(emptyString(env), {
        PendingTransaction *t = asTx(tx);
        if (!t) return emptyString(env);
        std::vector<std::string> ids = t->txid();
        return toJString(env, ids.empty() ? std::string() : ids.front());
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nCommit(
        JNIEnv *, jclass, jlong tx) {
    JNI_GUARD(JNI_FALSE, {
        PendingTransaction *t = asTx(tx);
        return t && t->commit("", false) ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT void JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nDisposeTx(
        JNIEnv *, jclass, jlong h, jlong tx) {
    JNI_GUARD_VOID({

        Wallet *parent = nullptr;
        PendingTransaction *t = regTakeTx(tx, &parent);
        if (parent && t) parent->disposeTransaction(t);
        (void) h;
    })
}

JNIEXPORT jobjectArray JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxIds(
        JNIEnv *env, jclass, jlong tx) {
    JNI_GUARD(nullptr, {
        PendingTransaction *t = asTx(tx);
        if (!t) return (jobjectArray) nullptr;
        std::vector<std::string> ids = t->txid();
        jclass str = env->FindClass("java/lang/String");
        if (!str) { clearPending(env); return (jobjectArray) nullptr; }
        jobjectArray out = env->NewObjectArray((jsize) ids.size(), str, nullptr);
        if (!out) { clearPending(env); return (jobjectArray) nullptr; }
        for (size_t i = 0; i < ids.size(); i++) {
            jstring s = env->NewStringUTF(ids[i].c_str());
            if (!s) { clearPending(env); return (jobjectArray) nullptr; }
            env->SetObjectArrayElement(out, (jsize) i, s);
            clearPending(env);
            env->DeleteLocalRef(s);
        }
        return out;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxCount(
        JNIEnv *, jclass, jlong tx) {
    JNI_GUARD(NLONG_ERR, {
        PendingTransaction *t = asTx(tx);
        return t ? (jlong) t->txCount() : NLONG_ERR;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxDust(
        JNIEnv *, jclass, jlong tx) {
    JNI_GUARD(NLONG_ERR, {
        PendingTransaction *t = asTx(tx);
        return t ? (jlong) t->dust() : NLONG_ERR;
    })
}

JNIEXPORT jlong JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nTxChange(
        JNIEnv *, jclass, jlong tx) {
    JNI_GUARD(NLONG_ERR, {
        PendingTransaction *t = asTx(tx);
        if (!t) return NLONG_ERR;
        Monero::PendingTransactionImpl *ti =
                static_cast<Monero::PendingTransactionImpl *>(t);
        uint64_t change = 0;
        for (const auto &ptx : ti->m_pending_tx) change += ptx.change_dts.amount;
        return (jlong) change;
    })
}

JNIEXPORT jint JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nAddressKind(
        JNIEnv *env, jclass, jstring address) {
    JNI_GUARD(0, {
        cryptonote::address_parse_info info;
        if (!cryptonote::get_account_address_from_str(
                info, cryptonote::MAINNET, toStd(env, address))) {
            return (jint) 0;
        }
        if (info.has_payment_id) return (jint) 3;
        if (info.is_subaddress) return (jint) 2;
        return (jint) 1;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nWaitRefreshIdle(
        JNIEnv *, jclass, jlong h, jlong timeoutMs) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        if (!w) return JNI_FALSE;
        Monero::WalletImpl *wi = static_cast<Monero::WalletImpl *>(w);
        if (timeoutMs < 0) timeoutMs = 0;
        if (timeoutMs > 5000) timeoutMs = 5000;
        auto deadline = std::chrono::steady_clock::now()
                + std::chrono::milliseconds(timeoutMs);
        for (;;) {
            if (wi->m_refreshMutex2.try_lock()) {
                wi->m_refreshMutex2.unlock();
                return JNI_TRUE;
            }
            if (std::chrono::steady_clock::now() >= deadline) return JNI_FALSE;
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
    })
}

namespace {

typedef std::map<std::string, jlong> TxCodeMap;

bool isTxHashHex(const std::string &s) {
    if (s.size() != 64) return false;
    for (char c : s) {
        bool ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        if (!ok) return false;
    }
    return true;
}

}

JNIEXPORT jlongArray JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nLookupTxs(
        JNIEnv *env, jclass, jlong h, jobjectArray txids, jlong timeoutMs) {
    JNI_GUARD(nullptr, {
        Wallet *w = asWallet(h);
        if (!w || !txids) return (jlongArray) nullptr;
        jsize n = env->GetArrayLength(txids);
        std::vector<jlong> codes((size_t) n, (jlong) -1);
        std::vector<std::string> ids((size_t) n);
        cryptonote::COMMAND_RPC_GET_TRANSACTIONS::request req = AUTO_VAL_INIT(req);
        cryptonote::COMMAND_RPC_GET_TRANSACTIONS::response res = AUTO_VAL_INIT(res);
        req.decode_as_json = false;
        req.prune = true;
        for (jsize i = 0; i < n; i++) {
            jstring js = (jstring) env->GetObjectArrayElement(txids, i);
            std::string s = toStd(env, js);
            if (js) env->DeleteLocalRef(js);
            if (isTxHashHex(s)) {
                ids[(size_t) i] = s;
                req.txs_hashes.push_back(s);
            }
        }
        if (!req.txs_hashes.empty()) {
            Monero::WalletImpl *wi = static_cast<Monero::WalletImpl *>(w);
            tools::wallet2 *w2 = wi->m_wallet.get();
            if (timeoutMs < 1000) timeoutMs = 1000;
            if (timeoutMs > 30000) timeoutMs = 30000;
            bool r = w2 && w2->invoke_http_json("/get_transactions", req, res,
                    std::chrono::milliseconds(timeoutMs));
            if (r && res.status == CORE_RPC_STATUS_OK) {

                std::set<std::string> requested;
                for (const auto &id : ids) if (!id.empty()) requested.insert(id);
                TxCodeMap resolved;
                std::set<std::string> contradictory;
                bool structOk = true;
                const uint64_t maxHeight = 1000000000ULL;
                const uint64_t maxSigned =
                        (uint64_t) std::numeric_limits<int64_t>::max();
                for (const auto &e : res.txs) {
                    if (!isTxHashHex(e.tx_hash) || requested.count(e.tx_hash) == 0) {
                        structOk = false;
                        break;
                    }
                    jlong code;
                    if (e.in_pool) {
                        code = (jlong) -3;
                    } else if (e.block_height > maxSigned
                            || e.block_height >= maxHeight) {
                        contradictory.insert(e.tx_hash);
                        continue;
                    } else {
                        code = (jlong) e.block_height;
                    }
                    auto it = resolved.find(e.tx_hash);
                    if (it != resolved.end()) {
                        contradictory.insert(e.tx_hash);
                    } else {
                        resolved.emplace(e.tx_hash, code);
                    }
                }
                std::set<std::string> missed;
                if (structOk) {
                    for (const auto &m : res.missed_tx) {
                        if (!isTxHashHex(m) || requested.count(m) == 0) {
                            structOk = false;
                            break;
                        }
                        if (resolved.count(m) || missed.count(m)) {
                            contradictory.insert(m);
                        }
                        missed.insert(m);
                    }
                }
                if (structOk) {
                    for (size_t i = 0; i < ids.size(); i++) {
                        if (ids[i].empty()) continue;
                        if (contradictory.count(ids[i])) {
                            codes[i] = (jlong) -1;
                            continue;
                        }
                        auto it = resolved.find(ids[i]);
                        if (it != resolved.end()) {
                            codes[i] = it->second;
                        } else if (missed.count(ids[i])) {
                            codes[i] = (jlong) -2;
                        }
                    }
                }
            }
        }
        jlongArray out = env->NewLongArray(n);
        if (!out) { clearPending(env); return (jlongArray) nullptr; }
        env->SetLongArrayRegion(out, 0, n, codes.data());
        clearPending(env);
        return out;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nSetupBackgroundSync(
        JNIEnv *env, jclass, jlong h, jbyteArray walletPassword,
        jbyteArray backgroundPassword) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        if (!w) return JNI_FALSE;
        std::string wp = bytesToStd(env, walletPassword);
        std::string bp = bytesToStd(env, backgroundPassword);
        bool ok = w->setupBackgroundSync(
                Wallet::BackgroundSync_CustomPassword, wp,
                Monero::optional<std::string>(bp));
        wipe(wp);
        wipe(bp);
        return ok ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nStartBackgroundSync(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        return (w && w->startBackgroundSync()) ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nStopBackgroundSync(
        JNIEnv *env, jclass, jlong h, jbyteArray walletPassword) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        if (!w) return JNI_FALSE;
        std::string wp = bytesToStd(env, walletPassword);
        bool ok = w->stopBackgroundSync(wp);
        wipe(wp);
        return ok ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nIsBackgroundSyncing(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        return (w && w->isBackgroundSyncing()) ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nIsBackgroundWallet(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        return (w && w->isBackgroundWallet()) ? JNI_TRUE : JNI_FALSE;
    })
}

JNIEXPORT jint JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nBackgroundSyncType(
        JNIEnv *, jclass, jlong h) {
    JNI_GUARD(-1, {
        Wallet *w = asWallet(h);
        return w ? (jint) w->getBackgroundSyncType() : (jint) -1;
    })
}

JNIEXPORT jboolean JNICALL
Java_com_professor_zerion_android_vault_wallet_xmr_NativeMonero_nSetPassword(
        JNIEnv *env, jclass, jlong h, jbyteArray password) {
    JNI_GUARD(JNI_FALSE, {
        Wallet *w = asWallet(h);
        if (!w) return JNI_FALSE;
        std::string pw = bytesToStd(env, password);
        bool ok = w->setPassword(pw);
        wipe(pw);
        return ok ? JNI_TRUE : JNI_FALSE;
    })
}

}
