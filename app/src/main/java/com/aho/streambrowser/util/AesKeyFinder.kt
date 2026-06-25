package com.aho.streambrowser.util

import android.util.Log

/** C1: Kẻ cướp khóa AES tại trận (God-Mode API Hooking)
 * ĐÃ ĐẬP BỎ THUẬT TOÁN REGEX CŨ.
 * Lớp này giờ chỉ chứa Payload Javascript để tiêm vào WebView.
 */
object AesKeyFinder {

    // Đây là Payload tàn bạo nhất: Nó ghi đè (Override) thẳng vào hàm giải mã gốc của trình duyệt
    val cryptoHookPayload = """
        (function() {
            if (window.__cryptoHooked) return;
            window.__cryptoHooked = true;
            
            // 1. Hook vào WebCrypto API (SubtleCrypto)
            if (window.crypto && window.crypto.subtle) {
                const originalDecrypt = window.crypto.subtle.decrypt;
                window.crypto.subtle.decrypt = async function(algorithm, key, data) {
                    try {
                        // Trích xuất Key Material thô từ bộ nhớ trình duyệt
                        const exportedKey = await window.crypto.subtle.exportKey("raw", key);
                        const keyHex = Array.from(new Uint8Array(exportedKey)).map(b => b.toString(16).padStart(2, '0')).join('');
                        
                        let ivHex = "";
                        if (algorithm.iv) {
                            ivHex = Array.from(new Uint8Array(algorithm.iv)).map(b => b.toString(16).padStart(2, '0')).join('');
                        }
                        
                        // Báo cáo về cho Kotlin qua Bridge
                        if (window.streamBridge) {
                            window.streamBridge.onCryptoKeyIntercepted(algorithm.name || "AES", keyHex, ivHex);
                        }
                    } catch(e) { /* Bỏ qua lỗi để không làm sập web */ }
                    
                    // Trả lại luồng giải mã nguyên thủy để video vẫn chạy bình thường
                    return originalDecrypt.apply(this, arguments);
                };
            }
            
            // 2. Hook vào CryptoJS (Rất nhiều web lậu dùng thư viện cũ này)
            let checkCryptoJS = setInterval(function() {
                if (window.CryptoJS && window.CryptoJS.AES && !window.__cryptoJsHooked) {
                    window.__cryptoJsHooked = true;
                    const originalAesDecrypt = window.CryptoJS.AES.decrypt;
                    
                    window.CryptoJS.AES.decrypt = function(ciphertext, key, cfg) {
                        try {
                            const keyHex = key.toString(window.CryptoJS.enc.Hex);
                            let ivHex = cfg && cfg.iv ? cfg.iv.toString(window.CryptoJS.enc.Hex) : "";
                            if (window.streamBridge) {
                                window.streamBridge.onCryptoKeyIntercepted("CryptoJS-AES", keyHex, ivHex);
                            }
                        } catch(e) { }
                        return originalAesDecrypt.apply(this, arguments);
                    };
                    clearInterval(checkCryptoJS);
                }
            }, 500); // Quét mỗi nửa giây xem Web có tải CryptoJS không
        })();
    """.trimIndent()
}
