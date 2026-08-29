package com.canace.x3offlinereader.builder;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import com.canace.x3offlinereader.builder.spi.TtsProvider;

/** Dependency-free Windows desktop front end over the same conversion coordinator as CLI. */
final class BuilderGui {
    private static final String AUTHOR="author", PROVIDER="tts.provider", RATE="tts.rate", PERFORMANCE="melo.performance";
    private static final String SEPARATE="output.separate", SOURCE_OUTPUT="output.sourceDirectory";
    private static final String MERGED_OUTPUT="output.mergedPath", SEPARATE_OUTPUT="output.separatePath";
    private static final String INPUT_DIR="picker.inputDirectory", OUTPUT_DIR="picker.outputDirectory", COVER_DIR="picker.coverDirectory";

    private final Path root;
    private final Preferences preferences;
    private final BuildCheckpointStore checkpointStore;
    private final BuilderCacheManager cacheManager;
    private final JFrame frame=new JFrame("听读工坊");
    private final DefaultListModel<Path> inputs=new DefaultListModel<>();
    private final JList<Path> inputList=new JList<>(inputs);
    private final JTextField output=new JTextField(),title=new JTextField(),author=new JTextField(),cover=new JTextField();
    private final JComboBox<String> voice=new JComboBox<>();
    private final JComboBox<TtsProviderCatalog.Entry> tts=new JComboBox<>(TtsProviderCatalog.guiEntries().toArray(TtsProviderCatalog.Entry[]::new));
    private final JComboBox<String> performance=new JComboBox<>(new String[]{"加速（推荐，最多 2 进程）","节省内存（1 进程）"});
    private final JSpinner rate=new JSpinner(new SpinnerNumberModel(1.0,0.5,2.0,0.05));
    private final JCheckBox separate=new JCheckBox("分别导出"),outputToSource=new JCheckBox("输出到源目录");
    private final JTextArea log=new JTextArea(5,50);
    private final JProgressBar progress=new JProgressBar(0,100);
    private final JLabel eta=new JLabel("预计总用时：--　预计剩余：--");
    private final JLabel timing=new JLabel("开始：--　已运行：00:00　当前阶段：00:00");
    private final JButton build=new JButton("开始转换"),chooseOutput=new JButton("选择"),previewButton=new JButton("试听");
    private final JButton resumeButton=new JButton("恢复未完成"),cacheButton=new JButton("缓存管理"),pauseExitButton=new JButton("保存进度并关闭");
    private Clip previewClip;
    private String previousProvider;
    private final AtomicLong voiceLoadGeneration=new AtomicLong();
    private boolean previousSeparate;
    private boolean manualOutputChosen,manualBeforeSource,updatingOutput;
    private volatile BuildControl activeControl;
    private volatile SwingWorker<Void,String> activeWorker;
    private volatile BuildCheckpointStore.Checkpoint activeCheckpoint;
    private volatile boolean closeAfterStop;

    BuilderGui(Path root){
        this.root=root;
        checkpointStore=new BuildCheckpointStore(root);cacheManager=new BuilderCacheManager(root);
        Preferences candidate;
        try{candidate=Preferences.userNodeForPackage(BuilderGui.class);}catch(RuntimeException unavailable){candidate=null;}
        preferences=candidate;
        author.setText(read(AUTHOR,""));
        previousProvider=read(PROVIDER,"melo");if(TtsProviderCatalog.guiEntries().stream().noneMatch(entry->entry.id().equals(previousProvider)))previousProvider="melo";
        tts.setSelectedItem(TtsProviderCatalog.entry(previousProvider));
        loadVoices(previousProvider,read(voiceKey(previousProvider),defaultVoice(previousProvider)));
        rate.setValue(Math.max(0.5,Math.min(2.0,readDouble(RATE,1.0))));
        performance.setSelectedIndex(Math.max(0,Math.min(1,readInt(PERFORMANCE,0))));
        separate.setSelected(readBoolean(SEPARATE,false));
        previousSeparate=separate.isSelected();
        outputToSource.setSelected(readBoolean(SOURCE_OUTPUT,false));
        output.setText(separate.isSelected()?read(SEPARATE_OUTPUT,""):"");
        manualOutputChosen=separate.isSelected()&&!output.getText().isBlank();
    }

    void show(){
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);frame.setMinimumSize(new Dimension(820,680));frame.setSize(960,780);frame.setLocationRelativeTo(null);
        Path windowIcon=root.resolve("builder/assets/tingdu-workshop-icon.png");try{if(Files.isRegularFile(windowIcon))frame.setIconImage(ImageIO.read(windowIcon.toFile()));}catch(Exception ignored){}
        frame.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){handleWindowClose();}});
        JPanel content=new JPanel(new BorderLayout(8,8));content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));frame.setContentPane(content);
        JPanel sources=new JPanel(new BorderLayout(6,6));sources.setBorder(BorderFactory.createTitledBorder("来源文件（支持多选、排序与合并）"));sources.add(new JScrollPane(inputList),BorderLayout.CENTER);
        JPanel inputButtons=new JPanel(new FlowLayout(FlowLayout.LEFT));button(inputButtons,"添加 EPUB/TXT",this::add);button(inputButtons,"移除",this::remove);button(inputButtons,"上移",()->move(-1));button(inputButtons,"下移",()->move(1));resumeButton.addActionListener(e->resumeLatest());inputButtons.add(resumeButton);cacheButton.addActionListener(e->showCacheManager());inputButtons.add(cacheButton);sources.add(inputButtons,BorderLayout.SOUTH);
        JPanel form=new JPanel(new GridLayout(0,2,8,8));form.setBorder(BorderFactory.createTitledBorder("输出与语音"));
        form.add(new JLabel("书名（合并时可选）"));form.add(title);form.add(new JLabel("作者（可选）"));form.add(author);form.add(new JLabel("语音模型（标明离线/在线）"));form.add(tts);form.add(new JLabel("声音"));JPanel voicePanel=new JPanel(new BorderLayout());voicePanel.add(voice);previewButton.addActionListener(e->preview());voicePanel.add(previewButton,BorderLayout.EAST);form.add(voicePanel);form.add(new JLabel("语速"));form.add(rate);form.add(new JLabel("Melo 性能"));form.add(performance);
        form.add(new JLabel("封面（可选）"));JPanel coverPanel=new JPanel(new BorderLayout());coverPanel.add(cover);JButton coverChoose=new JButton("选择");coverChoose.addActionListener(e->chooseCover());coverPanel.add(coverChoose,BorderLayout.EAST);form.add(coverPanel);
        form.add(new JLabel("模式"));JPanel modes=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));modes.add(separate);modes.add(outputToSource);form.add(modes);
        form.add(new JLabel("输出文件/目录"));JPanel target=new JPanel(new BorderLayout());target.add(output);chooseOutput.addActionListener(e->chooseOutput());target.add(chooseOutput,BorderLayout.EAST);form.add(target);
        JPanel lower=new JPanel(new BorderLayout(8,8));lower.add(form,BorderLayout.NORTH);log.setEditable(false);log.setLineWrap(true);JScrollPane logPane=new JScrollPane(log);logPane.setBorder(BorderFactory.createTitledBorder("转换里程碑与诊断"));lower.add(logPane,BorderLayout.CENTER);build.addActionListener(e->convert());pauseExitButton.setEnabled(false);pauseExitButton.addActionListener(e->requestPauseAndClose());progress.setStringPainted(true);JPanel labels=new JPanel(new GridLayout(0,1));labels.add(eta);labels.add(timing);JPanel progressPanel=new JPanel(new BorderLayout(0,2));progressPanel.add(progress,BorderLayout.CENTER);progressPanel.add(labels,BorderLayout.SOUTH);JPanel actionButtons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));actionButtons.add(pauseExitButton);actionButtons.add(build);JPanel actions=new JPanel(new BorderLayout(8,0));actions.add(progressPanel,BorderLayout.CENTER);actions.add(actionButtons,BorderLayout.EAST);lower.add(actions,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,sources,lower);split.setResizeWeight(0.27);split.setDividerLocation(205);split.setContinuousLayout(true);content.add(split,BorderLayout.CENTER);
        tts.addActionListener(e->changeProvider());voice.addActionListener(e->updatePreviewButton());separate.addActionListener(e->changeOutputMode());outputToSource.addActionListener(e->changeSourceOutput());
        watch(title,this::refreshOutputTarget);watch(author,this::refreshOutputTarget);watch(output,()->{if(!updatingOutput)manualOutputChosen=true;});
        performance.setEnabled("melo".equals(selectedProviderId()));refreshOutputControls();refreshResumeButton();frame.setVisible(true);SwingUtilities.invokeLater(this::offerResumeOnStartup);
    }

    private void button(JPanel panel,String label,Runnable action){JButton button=new JButton(label);button.addActionListener(e->action.run());panel.add(button);}

    private void add(){
        if(activeWorker!=null)return;
        JFileChooser chooser=chooserAt(INPUT_DIR);chooser.setMultiSelectionEnabled(true);chooser.setFileFilter(new FileNameExtensionFilter("EPUB 或 TXT","epub","txt"));
        if(chooser.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION){for(File file:chooser.getSelectedFiles())inputs.addElement(file.toPath());saveDirectory(INPUT_DIR,chooser.getCurrentDirectory());refreshOutputTarget();}
    }
    private void remove(){if(activeWorker!=null)return;int index=inputList.getSelectedIndex();if(index>=0){inputs.remove(index);refreshOutputTarget();}}
    private void move(int delta){if(activeWorker!=null)return;int index=inputList.getSelectedIndex(),next=index+delta;if(index<0||next<0||next>=inputs.size())return;Path value=inputs.remove(index);inputs.add(next,value);inputList.setSelectedIndex(next);refreshOutputTarget();}

    private void chooseOutput(){
        JFileChooser chooser=chooserAt(OUTPUT_DIR);
        if(separate.isSelected())chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        else{chooser.setSelectedFile(new File(chooser.getCurrentDirectory(),suggestedOutputName(inputValues(),title.getText(),author.getText())));chooser.setFileFilter(new FileNameExtensionFilter("OPBook","opbook"));}
        if(chooser.showSaveDialog(frame)!=JFileChooser.APPROVE_OPTION)return;
        File selected=chooser.getSelectedFile();String path=selected.getAbsolutePath();if(!separate.isSelected()&&!path.toLowerCase().endsWith(".opbook"))path+=".opbook";
        setOutputText(path);manualOutputChosen=true;write(separate.isSelected()?SEPARATE_OUTPUT:MERGED_OUTPUT,path);saveDirectory(OUTPUT_DIR,separate.isSelected()?selected:selected.getParentFile());
    }
    private void chooseCover(){if(activeWorker!=null)return;JFileChooser chooser=chooserAt(COVER_DIR);chooser.setFileFilter(new FileNameExtensionFilter("图片","jpg","jpeg","png","webp"));if(chooser.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION){cover.setText(chooser.getSelectedFile().getAbsolutePath());saveDirectory(COVER_DIR,chooser.getCurrentDirectory());}}

    private void changeProvider(){String selected=selectedProviderId();performance.setEnabled("melo".equals(selected));if(selected.equals(previousProvider))return;write(voiceKey(previousProvider),selectedVoice());previousProvider=selected;loadVoices(selected,read(voiceKey(selected),defaultVoice(selected)));}
    private String selectedProviderId(){Object selected=tts.getSelectedItem();return selected instanceof TtsProviderCatalog.Entry entry?entry.id():"melo";}
    private String selectedVoice(){Object selected=voice.getSelectedItem();return selected==null?defaultVoice(selectedProviderId()):selected.toString();}
    private void loadVoices(String provider,String preferred){long generation=voiceLoadGeneration.incrementAndGet();voice.removeAllItems();voice.setEnabled(false);previewButton.setEnabled(false);voice.setToolTipText("正在读取预生成音色清单…");new SwingWorker<List<String>,Void>(){private TtsProvider engine;@Override protected List<String> doInBackground(){List<String> cached=VoicePreviewLibrary.cachedVoices(root,provider);if(!cached.isEmpty())return cached;engine=createProvider(provider);return engine.voices();}@Override protected void done(){try{if(generation!=voiceLoadGeneration.get()||!provider.equals(selectedProviderId()))return;List<String> values=get();for(String value:values)voice.addItem(value);voice.setEnabled(!values.isEmpty());voice.setToolTipText(values.isEmpty()?"该语音模型尚未安装或当前不可用":null);if(!values.isEmpty())voice.setSelectedItem(values.contains(preferred)?preferred:values.get(0));updatePreviewButton();}catch(Exception failure){voice.setToolTipText("读取音色失败，请切换模型后重试");updatePreviewButton();}finally{if(engine instanceof AutoCloseable)try{((AutoCloseable)engine).close();}catch(Exception ignored){}}}}.execute();}
    private TtsProvider createProvider(String provider){return TtsProviderCatalog.previewProvider(provider,root,null);}
    private void updatePreviewButton(){String provider=selectedProviderId(),selected=selectedVoice();boolean ready=voice.getSelectedItem()!=null&&VoicePreviewLibrary.available(root,provider,selected);previewButton.setEnabled(ready);previewButton.setToolTipText(ready?"播放本地预生成的固定自然语句（1×，无需加载模型或联网）":"此音色的预生成试听资源尚未安装");}
    private void preview(){String provider=selectedProviderId(),selected=selectedVoice();Path wave=VoicePreviewLibrary.path(root,provider,selected);if(!VoicePreviewLibrary.available(root,provider,selected)){updatePreviewButton();JOptionPane.showMessageDialog(frame,"该音色的预生成试听资源尚未安装，请更新听读工坊资源包。");return;}try{if(previewClip!=null){previewClip.stop();previewClip.close();}previewClip=AudioSystem.getClip();previewClip.open(AudioSystem.getAudioInputStream(wave.toFile()));previewClip.start();log.append("播放预生成试听："+selected+"（固定自然语句，1×，本地即时播放）\n");}catch(Exception failure){JOptionPane.showMessageDialog(frame,"试听失败："+safeMessage(failure));}}
    private void changeOutputMode(){if(!outputToSource.isSelected()&&!output.getText().isBlank())write(previousSeparate?SEPARATE_OUTPUT:MERGED_OUTPUT,output.getText().trim());previousSeparate=separate.isSelected();manualOutputChosen=separate.isSelected()&&!read(SEPARATE_OUTPUT,"").isBlank();setOutputText(separate.isSelected()?read(SEPARATE_OUTPUT,""):"");refreshOutputControls();}
    private void changeSourceOutput(){if(outputToSource.isSelected()){manualBeforeSource=manualOutputChosen;if(!output.getText().isBlank())write(separate.isSelected()?SEPARATE_OUTPUT:MERGED_OUTPUT,output.getText().trim());}else{String saved=read(separate.isSelected()?SEPARATE_OUTPUT:MERGED_OUTPUT,"");manualOutputChosen=(separate.isSelected()||manualBeforeSource)&&!saved.isBlank();setOutputText(manualOutputChosen?saved:"");}refreshOutputControls();}
    private void rememberManualOutput(){if(!outputToSource.isSelected()&&!output.getText().isBlank())write(separate.isSelected()?SEPARATE_OUTPUT:MERGED_OUTPUT,output.getText().trim());}
    private void refreshOutputControls(){output.setEditable(!outputToSource.isSelected());chooseOutput.setEnabled(!outputToSource.isSelected());refreshOutputTarget();}
    private void refreshOutputTarget(){if(outputToSource.isSelected()){if(inputs.isEmpty()){setOutputText(separate.isSelected()?"每个源文件所在目录":"第一个源文件所在目录");return;}setOutputText(separate.isSelected()?"每个源文件所在目录":mergedSourceOutput(inputValues(),title.getText(),author.getText()).toString());}else if(!separate.isSelected()&&!manualOutputChosen){if(inputs.isEmpty()){setOutputText("");return;}Path directory=lastOutputDirectory();setOutputText(directory.resolve(suggestedOutputName(inputValues(),title.getText(),author.getText())).toString());}}
    private void setOutputText(String value){updatingOutput=true;try{output.setText(value);}finally{updatingOutput=false;}}

    private void convert(){
        if(inputs.isEmpty()){JOptionPane.showMessageDialog(frame,"请添加 EPUB/TXT 来源");return;}
        if(voice.getSelectedItem()==null){JOptionPane.showMessageDialog(frame,"当前语音引擎没有可用声音，请更换 TTS");return;}
        if(!outputToSource.isSelected()&&output.getText().isBlank()){JOptionPane.showMessageDialog(frame,"请选择输出位置");return;}
        BuilderOptions selected=options();if(!confirmOnline(selected))return;startConversion(selected,null,false);
    }

    private boolean confirmOnline(BuilderOptions selected){if(!TtsProviderCatalog.entry(selected.tts).online())return true;int answer=JOptionPane.showConfirmDialog(frame,"所选模型为在线语音。转换时，待朗读正文会发送给 Microsoft Edge 语音服务。\n不会保存 API Key（本服务也不需要 API Key）。是否继续？","在线语音隐私确认",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);return answer==JOptionPane.YES_OPTION;}

    private void startConversion(BuilderOptions selected,BuildCheckpointStore.Checkpoint resumeCheckpoint,boolean resuming){
        if(activeWorker!=null)return;saveReusableSettings();closeAfterStop=false;
        try{activeCheckpoint=resumeCheckpoint==null?checkpointStore.create(selected):checkpointStore.update(resumeCheckpoint,"running",0,"正在重新计算断点进度");}catch(Exception failure){JOptionPane.showMessageDialog(frame,"无法创建断点记录："+safeMessage(failure));return;}
        BuildControl control=new BuildControl();activeControl=control;setBusy(true);progress.setValue(0);progress.setString(null);eta.setText("预计总用时：正在分析文本与缓存…　预计剩余：--");log.setText(resuming?"正在从断点恢复；已完成句子和 Track 会直接复用…\n":"正在转换；可随时点击“保存进度并关闭”…\n");
        long started=System.nanoTime(),startedAt=System.currentTimeMillis();AtomicLong lastActivity=new AtomicLong(started),stageStarted=new AtomicLong(started),heartbeatFor=new AtomicLong(0);EtaTracker etaTracker=new EtaTracker();
        Timer etaTimer=new Timer(1000,event->{long now=System.nanoTime(),seconds=Math.max(0,(now-started)/1_000_000_000L),stage=Math.max(0,(now-stageStarted.get())/1_000_000_000L);eta.setText(etaTracker.text(seconds,false));timing.setText("开始："+LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedAt),java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))+"　已运行："+formatDuration(seconds)+"　当前阶段："+formatDuration(stage));long activity=lastActivity.get();if(now-activity>=java.util.concurrent.TimeUnit.MINUTES.toNanos(10)&&heartbeatFor.get()!=activity){heartbeatFor.set(activity);log.append("[+"+elapsed(started)+"] 当前步骤已 10 分钟没有新里程碑；转换器仍在等待语音/编码进程，12 分钟超时边界会自动重试一次。\n");log.setCaretPosition(log.getDocument().getLength());}});etaTimer.start();
        SwingWorker<Void,String> worker=new SwingWorker<>(){
            private void update(int percent,String message){long now=System.nanoTime();lastActivity.set(now);int safe=Math.max(0,Math.min(100,percent));setProgress(safe);if(message!=null){stageStarted.set(now);publish("[+"+elapsed(started)+"] "+message);try{activeCheckpoint=checkpointStore.update(activeCheckpoint,"running",safe,message);}catch(Exception ignored){publish("[+"+elapsed(started)+"] 警告：断点状态写入失败，但语音缓存仍会继续保存");}}}
            private void workerUpdate(int percent,String message){update(percent,message);}
            private BuildProgress listener(int fileIndex,int count){return new BuildProgress(){@Override public void update(int value,String message){workerUpdate((fileIndex*100+value)/count,message);}@Override public void estimateSeconds(long seconds){long elapsed=Math.max(0,(System.nanoTime()-started)/1_000_000_000L);etaTracker.seed(elapsed+seconds*(count-fileIndex));}};}
            @Override protected Void doInBackground()throws Exception{
                AudiobookBuilder builder=new AudiobookBuilder(root);
                if(selected.separate){if(!selected.outputToSource)Files.createDirectories(selected.output);int count=selected.inputs.size();for(int index=0;index<count;index++){control.checkpoint();Path input=selected.inputs.get(index);BuilderOptions one=copy(selected);one.inputs.add(input);one.title=null;one.separate=false;String name=suggestedOutputName(List.of(input),null,selected.author);one.output=selected.outputToSource?sourceOutput(input,selected.author):selected.output.resolve(name);publish("[+"+elapsed(started)+"] 文件 "+(index+1)+"/"+count+"："+input.getFileName());Path result=builder.build(one,listener(index,count),control);publish("[+"+elapsed(started)+"] 完成："+result);}}
                else{Path result=builder.build(selected,listener(0,1),control);publish("[+"+elapsed(started)+"] 完成："+result);}return null;
            }
            @Override protected void process(List<String> lines){for(String line:lines)log.append(line+"\n");log.setCaretPosition(log.getDocument().getLength());}
            @Override protected void done(){
                etaTimer.stop();long seconds=Math.max(0,(System.nanoTime()-started)/1_000_000_000L);boolean paused=false;
                try{get();try{checkpointStore.complete(activeCheckpoint);}catch(Exception checkpointFailure){log.append("警告：输出已完成，但断点登记清理失败。\n");}progress.setValue(100);eta.setText(etaText(seconds,seconds,true));log.append("全部完成（"+elapsed(started)+"）。\n");}
                catch(Exception error){Throwable cause=rootCause(error);paused=cause instanceof BuildControl.PauseRequestedException||control.isStopRequested();String message=safeMessage(cause);try{activeCheckpoint=checkpointStore.update(activeCheckpoint,paused?"paused":"failed",progress.getValue(),message);}catch(Exception ignored){}if(paused){progress.setString("已保存："+progress.getValue()+"%");eta.setText("已保存断点　已运行："+formatDuration(seconds));log.append(message+"\n");}else{progress.setString("失败："+progress.getValue()+"%");eta.setText("已运行："+formatDuration(seconds)+"　预计信息已停止");log.append("失败（"+elapsed(started)+"）："+message+"\n");}}
                log.setCaretPosition(log.getDocument().getLength());activeWorker=null;activeControl=null;setBusy(false);refreshResumeButton();boolean exit=closeAfterStop;closeAfterStop=false;if(exit)exitApplication();
            }
        };
        activeWorker=worker;worker.addPropertyChangeListener(event->{if("progress".equals(event.getPropertyName())){int value=(Integer)event.getNewValue();long seconds=Math.max(0,(System.nanoTime()-started)/1_000_000_000L);etaTracker.observe(value,seconds);progress.setValue(value);}});worker.execute();
    }

    private void setBusy(boolean busy){build.setEnabled(!busy);resumeButton.setEnabled(!busy&&checkpointStore.latest().isPresent());cacheButton.setEnabled(!busy);pauseExitButton.setEnabled(busy);inputList.setEnabled(!busy);title.setEnabled(!busy);author.setEnabled(!busy);cover.setEnabled(!busy);tts.setEnabled(!busy);voice.setEnabled(!busy);previewButton.setEnabled(!busy&&VoicePreviewLibrary.available(root,selectedProviderId(),selectedVoice()));performance.setEnabled(!busy&&"melo".equals(selectedProviderId()));rate.setEnabled(!busy);separate.setEnabled(!busy);outputToSource.setEnabled(!busy);output.setEnabled(!busy);chooseOutput.setEnabled(!busy&&!outputToSource.isSelected());}

    private void requestPauseAndClose(){BuildControl control=activeControl;if(control==null){exitApplication();return;}if(closeAfterStop)return;closeAfterStop=true;pauseExitButton.setEnabled(false);build.setEnabled(false);log.append("正在保存当前断点并停止语音/编码进程，请稍候…\n");try{activeCheckpoint=checkpointStore.update(activeCheckpoint,"pausing",progress.getValue(),"正在安全停止");}catch(Exception ignored){}control.requestStop();}

    private void handleWindowClose(){saveReusableSettings();if(activeControl!=null){Object[] choices={"保存进度并关闭","继续转换"};int answer=JOptionPane.showOptionDialog(frame,"转换仍在进行。选择“保存进度并关闭”后，已生成句子和已编码 Track 会保留，下次可以恢复。","转换尚未完成",JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,choices,choices[0]);if(answer==0)requestPauseAndClose();return;}exitApplication();}
    private void exitApplication(){saveReusableSettings();if(previewClip!=null){previewClip.stop();previewClip.close();previewClip=null;}frame.dispose();System.exit(0);}

    private void refreshResumeButton(){resumeButton.setEnabled(activeWorker==null&&checkpointStore.latest().isPresent());}
    private void offerResumeOnStartup(){if(activeWorker!=null)return;checkpointStore.latest().ifPresent(checkpoint->{String source=checkpoint.options().inputs.isEmpty()?"未知来源":checkpoint.options().inputs.get(0).getFileName().toString();int answer=JOptionPane.showConfirmDialog(frame,"发现未完成任务："+source+"\n上次状态："+checkpoint.message()+"（约 "+checkpoint.percent()+"%）\n是否现在恢复？","恢复未完成转换",JOptionPane.YES_NO_OPTION,JOptionPane.QUESTION_MESSAGE);if(answer==JOptionPane.YES_OPTION)resume(checkpoint);});}
    private void resumeLatest(){checkpointStore.latest().ifPresentOrElse(this::resume,()->JOptionPane.showMessageDialog(frame,"没有可恢复的转换任务。"));}
    private void resume(BuildCheckpointStore.Checkpoint checkpoint){BuilderOptions selected=checkpoint.options();List<Path> missing=selected.inputs.stream().filter(path->!Files.isRegularFile(path)).toList();if(!missing.isEmpty()){JOptionPane.showMessageDialog(frame,"无法恢复，来源文件已移动或删除：\n"+missing.get(0));return;}if(selected.output==null){JOptionPane.showMessageDialog(frame,"无法恢复：断点没有有效输出位置。");return;}if(!confirmOnline(selected))return;applyOptionsToUi(selected);startConversion(selected,checkpoint,true);}
    private void applyOptionsToUi(BuilderOptions selected){inputs.clear();for(Path path:selected.inputs)inputs.addElement(path);title.setText(selected.title==null?"":selected.title);author.setText(selected.author==null?"":selected.author);cover.setText(selected.cover==null?"":selected.cover.toString());rate.setValue(Math.max(0.5,Math.min(2.0,selected.rate)));performance.setSelectedIndex(selected.meloWorkers<=1?1:0);write(voiceKey(selected.tts),selected.voice);previousProvider=selected.tts;tts.setSelectedItem(TtsProviderCatalog.entry(selected.tts));loadVoices(selected.tts,selected.voice);separate.setSelected(selected.separate);previousSeparate=selected.separate;outputToSource.setSelected(selected.outputToSource);manualOutputChosen=!selected.outputToSource;setOutputText(selected.output.toString());refreshOutputControls();}

    private void showCacheManager(){cacheButton.setEnabled(false);new SwingWorker<BuilderCacheManager.Stats,Void>(){@Override protected BuilderCacheManager.Stats doInBackground(){return cacheManager.stats();}@Override protected void done(){cacheButton.setEnabled(activeWorker==null);try{BuilderCacheManager.Stats stats=get();String message="句子缓存："+stats.sentenceFiles()+" 个文件 / "+BuilderCacheManager.humanBytes(stats.sentenceBytes())+"\n未完成任务临时目录："+stats.unfinishedDirectories()+" 个 / "+BuilderCacheManager.humanBytes(stats.unfinishedBytes())+"\n\n成功转换后会自动删除本次 PCM/Track 临时文件；句子缓存超过 20 GB 或 180 天时按最旧优先整理。";Object[] choices={"执行自动整理","清理 30 天前临时任务","清空句子缓存","关闭"};int answer=JOptionPane.showOptionDialog(frame,message,"缓存管理",JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,choices,choices[3]);if(answer==0)runCacheAction("自动整理",()->cacheManager.pruneSentenceCache(BuilderCacheManager.DEFAULT_MAX_BYTES,BuilderCacheManager.DEFAULT_MAX_AGE));else if(answer==1){int confirm=JOptionPane.showConfirmDialog(frame,"30 天前的未完成任务临时 Track 将被删除；全局句子缓存仍保留。是否继续？","清理旧临时任务",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);if(confirm==JOptionPane.YES_OPTION)runCacheAction("清理旧临时任务",()->cacheManager.clearStaleWork(Duration.ofDays(30)));}else if(answer==2){int confirm=JOptionPane.showConfirmDialog(frame,"清空后，之后转换相同句子也必须重新生成语音。输出的 .opbook 不受影响。是否继续？","清空句子缓存",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);if(confirm==JOptionPane.YES_OPTION)runCacheAction("清空句子缓存",cacheManager::clearSentenceCache);}}catch(Exception failure){JOptionPane.showMessageDialog(frame,"缓存统计失败："+safeMessage(failure));}}}.execute();}
    private void runCacheAction(String label,java.util.concurrent.Callable<BuilderCacheManager.PruneResult> action){cacheButton.setEnabled(false);new SwingWorker<BuilderCacheManager.PruneResult,Void>(){@Override protected BuilderCacheManager.PruneResult doInBackground()throws Exception{return action.call();}@Override protected void done(){cacheButton.setEnabled(activeWorker==null);try{BuilderCacheManager.PruneResult result=get();JOptionPane.showMessageDialog(frame,label+"完成：处理 "+result.deletedFiles()+" 项，释放 "+BuilderCacheManager.humanBytes(result.deletedBytes()));}catch(Exception failure){JOptionPane.showMessageDialog(frame,label+"失败："+safeMessage(failure));}}}.execute();}
    private static Throwable rootCause(Throwable error){Throwable value=error;while(value.getCause()!=null&&value.getCause()!=value)value=value.getCause();return value;}

    private BuilderOptions options(){
        BuilderOptions options=new BuilderOptions();options.inputs.addAll(inputValues());options.title=title.getText().isBlank()?null:title.getText().trim();options.author=author.getText().trim();options.tts=selectedProviderId();options.voice=selectedVoice();options.rate=((Number)rate.getValue()).doubleValue();options.meloWorkers=performance.getSelectedIndex()==1?1:2;options.cover=cover.getText().isBlank()?null:Path.of(cover.getText());options.separate=separate.isSelected();options.outputToSource=outputToSource.isSelected();options.output=options.outputToSource?mergedSourceOutput(options.inputs,options.title,options.author):Path.of(output.getText());options.allowOnlineTts=TtsProviderCatalog.entry(options.tts).online();return options;
    }
    private static BuilderOptions copy(BuilderOptions source){BuilderOptions copy=new BuilderOptions();copy.title=source.title;copy.author=source.author;copy.language=source.language;copy.tts=source.tts;copy.voice=source.voice;copy.meloPython=source.meloPython;copy.meloWorkers=source.meloWorkers;copy.rate=source.rate;copy.pauseMs=source.pauseMs;copy.maxTrackMinutes=source.maxTrackMinutes;copy.ffmpeg=source.ffmpeg;copy.cover=source.cover;copy.createdAt=source.createdAt;copy.outputToSource=source.outputToSource;copy.allowOnlineTts=source.allowOnlineTts;return copy;}
    private List<Path> inputValues(){List<Path> values=new ArrayList<>();for(int i=0;i<inputs.size();i++)values.add(inputs.get(i));return values;}

    private void saveReusableSettings(){write(AUTHOR,author.getText().trim());write(PROVIDER,selectedProviderId());write(voiceKey(selectedProviderId()),selectedVoice());writeDouble(RATE,((Number)rate.getValue()).doubleValue());writeInt(PERFORMANCE,performance.getSelectedIndex());writeBoolean(SEPARATE,separate.isSelected());writeBoolean(SOURCE_OUTPUT,outputToSource.isSelected());rememberManualOutput();}
    private JFileChooser chooserAt(String key){JFileChooser chooser=new JFileChooser();String stored=read(key,"");Path initial=stored.isBlank()?defaultPickerDirectory():Path.of(stored);if(Files.isDirectory(initial))chooser.setCurrentDirectory(initial.toFile());return chooser;}
    private static Path defaultPickerDirectory(){Path home=Path.of(System.getProperty("user.home","."));Path downloads=home.resolve("Downloads");return Files.isDirectory(downloads)?downloads:home;}
    private void saveDirectory(String key,File directory){if(directory!=null&&directory.isDirectory())write(key,directory.getAbsolutePath());}
    private Path lastOutputDirectory(){String stored=read(OUTPUT_DIR,"");try{Path path=stored.isBlank()?null:Path.of(stored);if(path!=null&&Files.isDirectory(path))return path;}catch(RuntimeException ignored){}String prior=read(MERGED_OUTPUT,"");try{Path parent=prior.isBlank()?null:Path.of(prior).toAbsolutePath().getParent();if(parent!=null&&Files.isDirectory(parent))return parent;}catch(RuntimeException ignored){}return defaultPickerDirectory();}
    private static void watch(JTextField field,Runnable action){field.getDocument().addDocumentListener(new DocumentListener(){@Override public void insertUpdate(DocumentEvent event){action.run();}@Override public void removeUpdate(DocumentEvent event){action.run();}@Override public void changedUpdate(DocumentEvent event){action.run();}});}

    static Path mergedSourceOutput(List<Path> sources,String title,String author){if(sources.isEmpty())throw new IllegalArgumentException("At least one input is required");return sources.get(0).toAbsolutePath().getParent().resolve(suggestedOutputName(sources,title,author));}
    static Path sourceOutput(Path input,String author){return input.toAbsolutePath().getParent().resolve(suggestedOutputName(List.of(input),null,author));}
    static String suggestedOutputName(List<Path> sources,String title,String author){String book=title==null||title.isBlank()?(sources.isEmpty()?"book":strip(sources.get(0).getFileName().toString())):title.trim();return safeFilePart(book,120)+" ["+safeFilePart(AudiobookBuilder.effectiveAuthor(author),40)+"].opbook";}
    private static String safeFilePart(String value,int maximum){String safe=value.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").trim().replaceAll("[. ]+$","");if(safe.isBlank())safe="未命名";int count=safe.codePointCount(0,safe.length());return count<=maximum?safe:safe.substring(0,safe.offsetByCodePoints(0,maximum)).stripTrailing();}
    static String defaultVoice(String provider){return TtsProviderCatalog.entry(provider).defaultVoice();}
    private static String elapsed(long started){long seconds=Math.max(0,(System.nanoTime()-started)/1_000_000_000L);return String.format("%02d:%02d",seconds/60,seconds%60);}
    static String etaText(long elapsedSeconds,long predictedTotalSeconds,boolean finished){if(finished)return "实际用时："+formatDuration(elapsedSeconds)+"　预计剩余：00:00";if(predictedTotalSeconds<0)return "预计总用时：正在分析文本与缓存…　预计剩余：--";if(elapsedSeconds>=predictedTotalSeconds)return "预计总用时：约 "+formatDuration(predictedTotalSeconds)+"　预计剩余：正在重新校准…";return "预计总用时：约 "+formatDuration(predictedTotalSeconds)+"　预计剩余：约 "+formatDuration(predictedTotalSeconds-elapsedSeconds);}
    private static String formatDuration(long seconds){long hours=seconds/3600,minutes=(seconds%3600)/60,remaining=seconds%60;return hours>0?String.format("%d 小时 %02d 分 %02d 秒（%d:%02d:%02d）",hours,minutes,remaining,hours,minutes,remaining):String.format("%02d:%02d",minutes,remaining);}
    static final class EtaTracker{private long predicted=-1;private int lastProgress=-1;synchronized void seed(long seconds){if(seconds<=0)return;predicted=predicted<0?seconds:Math.round(predicted*0.7+seconds*0.3);}synchronized void observe(int percent,long elapsed){if(percent<=lastProgress)return;lastProgress=percent;if(percent<10||elapsed<3)return;long projection=Math.max(elapsed+1,Math.round(elapsed*100.0/percent));if(predicted<0)predicted=projection;else{long minimum=Math.max(elapsed+1,predicted/2),maximum=Math.max(minimum,predicted*2);projection=Math.max(minimum,Math.min(maximum,projection));predicted=Math.max(elapsed+1,Math.round(predicted*0.85+projection*0.15));}}synchronized String text(long elapsed,boolean finished){return etaText(elapsed,predicted,finished);}synchronized long predicted(){return predicted;}}
    private static String safeMessage(Throwable error){String message=error.getMessage();return message==null||message.isBlank()?error.getClass().getSimpleName():message;}
    private static String voiceKey(String provider){return "tts.voice."+(provider==null?"melo":provider);}
    private static String strip(String name){int dot=name.lastIndexOf('.');return dot>0?name.substring(0,dot):name;}

    private String read(String key,String fallback){try{return preferences==null?fallback:preferences.get(key,fallback);}catch(RuntimeException error){return fallback;}}
    private double readDouble(String key,double fallback){try{return preferences==null?fallback:preferences.getDouble(key,fallback);}catch(RuntimeException error){return fallback;}}
    private int readInt(String key,int fallback){try{return preferences==null?fallback:preferences.getInt(key,fallback);}catch(RuntimeException error){return fallback;}}
    private boolean readBoolean(String key,boolean fallback){try{return preferences==null?fallback:preferences.getBoolean(key,fallback);}catch(RuntimeException error){return fallback;}}
    private void write(String key,String value){try{if(preferences!=null)preferences.put(key,value==null?"":value);}catch(RuntimeException ignored){}}
    private void writeDouble(String key,double value){try{if(preferences!=null)preferences.putDouble(key,value);}catch(RuntimeException ignored){}}
    private void writeInt(String key,int value){try{if(preferences!=null)preferences.putInt(key,value);}catch(RuntimeException ignored){}}
    private void writeBoolean(String key,boolean value){try{if(preferences!=null)preferences.putBoolean(key,value);}catch(RuntimeException ignored){}}
}
