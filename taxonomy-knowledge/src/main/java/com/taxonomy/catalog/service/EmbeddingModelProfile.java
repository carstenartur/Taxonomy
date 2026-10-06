package com.taxonomy.catalog.service;

import java.util.Map;

/** Explicit, dimension-compatible inference contracts. Paths and URLs never select pooling semantics. */
public enum EmbeddingModelProfile {
    MULTILINGUAL_MINILM_L12(
            "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
            "e8f8c211226b894fcb81acc59f3b34ba3efd5f42", "onnx/model_quint8_avx2.onnx",
            "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f",
            "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8",
            "5036ea374ffedd706e3bef33e2e0d6953cb868ef8a490e76e32ba0faa37a6b9b",
            "378eb3bf733eb16e65792d7e3fda5b8a4631387ca04d2015199c4d4f22ae554d",
            "6300193cb75e01cf80c96decef7187dfb33094d97cc1490b7ead6ff134476e4e",
            "mean", 128, "", ""),
    BGE_SMALL_EN(
            "https://huggingface.co/BAAI/bge-small-en-v1.5",
            "5c38ec7c405ec4b44b94cc5a9bb96e735b38267a", "onnx/model.onnx",
            "828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35",
            "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
            "9261e7d79b44c8195c1cada2b453e55b00aeb81e907a6664974b4d7776172ab3",
            "b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3",
            "094f8e891b932f2000c92cfc663bac4c62069f5d8af5b5278c4306aef3084750",
            "cls", 512, "Represent this sentence for searching relevant passages: ", "");

    private final String modelUrl;
    private final String revision;
    private final String modelFile;
    private final String modelSha256;
    private final Map<String, String> fileSha256;
    private final String pooling;
    private final int maxTokens;
    private final String queryPrefix;
    private final String documentPrefix;

    EmbeddingModelProfile(String modelUrl, String revision, String modelFile, String modelSha256,
                          String tokenizerSha256, String tokenizerConfigSha256,
                          String specialTokensMapSha256, String configSha256,
                          String pooling, int maxTokens, String queryPrefix, String documentPrefix) {
        this.modelUrl = modelUrl;
        this.revision = revision;
        this.modelFile = modelFile;
        this.modelSha256 = modelSha256;
        this.fileSha256 = Map.of("model.onnx", modelSha256, "tokenizer.json", tokenizerSha256,
                "tokenizer_config.json", tokenizerConfigSha256, "special_tokens_map.json", specialTokensMapSha256,
                "config.json", configSha256);
        this.pooling = pooling;
        this.maxTokens = maxTokens;
        this.queryPrefix = queryPrefix;
        this.documentPrefix = documentPrefix;
    }

    public String modelUrl() { return modelUrl; }
    public String revision() { return revision; }
    public String modelFile() { return modelFile; }
    public String modelSha256() { return modelSha256; }
    public Map<String, String> fileSha256() { return fileSha256; }
    public String pooling() { return pooling; }
    public int maxTokens() { return maxTokens; }
    public String queryPrefix() { return queryPrefix; }
    public String documentPrefix() { return documentPrefix; }
}
