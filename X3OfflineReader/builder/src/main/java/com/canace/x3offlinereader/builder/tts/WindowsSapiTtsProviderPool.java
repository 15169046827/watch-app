package com.canace.x3offlinereader.builder.tts;

import com.canace.x3offlinereader.builder.model.SynthesisRequest;
import com.canace.x3offlinereader.builder.model.SynthesisResult;
import com.canace.x3offlinereader.builder.spi.TtsProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Independent long-lived System.Speech processes with deterministic ordered consumption upstream. */
public final class WindowsSapiTtsProviderPool implements TtsProvider,AutoCloseable {
    private final List<WindowsSapiTtsProvider> providers=new ArrayList<>();
    private final BlockingQueue<WindowsSapiTtsProvider> available;

    public WindowsSapiTtsProviderPool(Path root,int maximumWorkers){
        int workers=recommendedParallelism(Runtime.getRuntime().availableProcessors(),maximumWorkers);
        for(int index=0;index<workers;index++)providers.add(new WindowsSapiTtsProvider(root));
        available=new ArrayBlockingQueue<>(workers);available.addAll(providers);
    }
    static int recommendedParallelism(int processors,int maximumWorkers){return Math.max(1,Math.min(Math.min(12,maximumWorkers),Math.max(1,processors)));}
    @Override public String id(){return "windows-sapi-v2";}
    @Override public boolean isAvailable(){return !providers.isEmpty()&&providers.get(0).isAvailable();}
    @Override public List<String> voices(){return providers.isEmpty()?List.of():providers.get(0).voices();}
    @Override public int parallelism(){return providers.size();}
    @Override public SynthesisResult synthesize(SynthesisRequest request,Path workingDirectory)throws Exception{WindowsSapiTtsProvider provider=available.take();try{return provider.synthesize(request,workingDirectory);}finally{available.put(provider);}}
    @Override public void close(){providers.forEach(WindowsSapiTtsProvider::close);}
}
