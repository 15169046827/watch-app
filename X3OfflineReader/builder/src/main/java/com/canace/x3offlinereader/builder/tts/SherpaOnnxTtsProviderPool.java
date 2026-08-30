package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Small CPU pool for independent Kokoro/AISHELL-3 sentence inference. */
public final class SherpaOnnxTtsProviderPool implements TtsProvider,AutoCloseable {
    private final List<SherpaOnnxTtsProvider> providers=new ArrayList<>();
    private final BlockingQueue<SherpaOnnxTtsProvider> available;

    public SherpaOnnxTtsProviderPool(Path root,SherpaOnnxTtsProvider.Model model,int maximumWorkers){
        int processors=Runtime.getRuntime().availableProcessors();
        int workers=recommendedParallelism(processors,maximumWorkers);
        int threads=Math.max(1,Math.min(8,processors/workers));
        for(int index=0;index<workers;index++)providers.add(new SherpaOnnxTtsProvider(root,model,threads));
        available=new ArrayBlockingQueue<>(workers);available.addAll(providers);
    }
    static int recommendedParallelism(int processors,int maximumWorkers){int cap=processors>=12?4:processors>=6?2:1;return Math.max(1,Math.min(Math.min(4,maximumWorkers),cap));}
    @Override public String id(){return providers.get(0).id();}
    @Override public boolean isAvailable(){return !providers.isEmpty()&&providers.get(0).isAvailable();}
    @Override public List<String> voices(){return providers.isEmpty()?List.of():providers.get(0).voices();}
    @Override public int parallelism(){return providers.size();}
    @Override public SynthesisResult synthesize(SynthesisRequest request,Path workingDirectory)throws Exception{SherpaOnnxTtsProvider provider=available.take();try{return provider.synthesize(request,workingDirectory);}finally{available.put(provider);}}
    @Override public void close(){providers.forEach(SherpaOnnxTtsProvider::close);}
}
