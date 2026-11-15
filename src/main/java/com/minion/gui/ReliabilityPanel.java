package com.minion.gui;

import com.google.gson.*;
import com.minion.core.agent.*;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.config.ModelConfig;
import com.minion.core.llm.*;
import com.minion.core.tools.AtomicFiles;
import com.minion.gui.session.*;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Insets;
import javafx.stage.Window;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real controls for the reliability features; expensive IO/probes stay off the FX thread. */
public final class ReliabilityPanel extends VBox {
    private final TabPane tabs=new TabPane();
    private final SessionManager manager;private final SessionHandle handle;private final Window owner;
    private final Label feedback=new Label();private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    public ReliabilityPanel(Window owner,SessionManager manager,SessionHandle handle){this.owner=owner;this.manager=manager;this.handle=handle;
        tabs.getTabs().addAll(tab("验收 / 权限",rules()),tab("步骤恢复",steps()),tab("差异 / 回退",changes()),tab("模型自检 / 评测",probes()),tab("本地计划任务",jobs()));
        getChildren().addAll(tabs,feedback);VBox.setVgrow(tabs,Priority.ALWAYS);
    }
    private Tab tab(String name,javafx.scene.Node node){Tab t=new Tab(name,node);t.setClosable(false);return t;}
    private TextArea area(boolean editable){TextArea a=new TextArea();a.setEditable(editable);a.setWrapText(true);return a;}
    private Button button(String label,Runnable action){Button b=new Button(label);b.setOnAction(e->{try{action.run();}catch(Exception x){feedback.setText(x.getMessage());}});return b;}
    private VBox body(javafx.scene.Node... nodes){VBox b=new VBox(8,nodes);b.setPadding(new Insets(10));return b;}
    private void work(TextArea out,java.util.concurrent.Callable<String> action){out.setText("正在处理…");manager.background(()->{String text;try{text=action.call();}catch(Exception e){text="未完成: "+e.getMessage();}final String result=text;Platform.runLater(()->out.setText(result));});}
    private boolean confirm(String text){Alert a=new Alert(Alert.AlertType.CONFIRMATION,text,ButtonType.OK,ButtonType.CANCEL);a.initOwner(owner);Theme.style(a);return a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private VBox rules(){ReliabilityService service=handle.loop.reliability();ReliabilityService.Settings s=service.settings();TextArea input=area(true),output=area(false);input.setText(gson.toJson(s.rules));input.setPrefRowCount(7);CheckBox strict=new CheckBox("严格本地兼容模式（禁止任意Shell/Python，非OS沙箱）");strict.setSelected(s.strictCompatibility);
        TextArea example=area(false);example.setPrefRowCount(3);example.setText("示例：[{\"type\":\"table_rows\",\"path\":\"result.xlsx\",\"sheet\":\"Sheet1\",\"count\":353},{\"type\":\"table_columns\",\"path\":\"result.xlsx\",\"columns\":[\"报关单号\",\"原文摘录\"]}]\n支持file_exists/file_hash/json_count/table_rows/table_columns/unique_column/children_reviewed/tool_success。规则只绑定登记时的当前任务；换新任务需重新登记。不是自动证明业务内容准确。");
        VBox b=body(strict,new Label("规则由你登记；模型不能自行移除。XLSX/CSV首个非空行为表头，行数不计表头、空行。"),example,input,new HBox(8,button("登记规则",()->{String task; synchronized(handle.session.taskState){task=handle.session.taskState.id;}try{service.configure(task,input.getText(),strict.isSelected());feedback.setText("已登记到任务 "+task);}catch(Exception e){throw new IllegalStateException(e);}}),button("程序核验",()->work(output,()->gson.toJson(service.evaluate(handle.session.taskState,p->com.minion.core.tools.PathsGuard.inside(handle.loop.workspace().workDir(),p)||com.minion.core.tools.PathsGuard.insideExtra(handle.loop.workspace(),p)))))),output);VBox.setVgrow(output,Priority.ALWAYS);return b;
    }
    private VBox steps(){TextArea out=area(false);TextField id=new TextField(),note=new TextField();id.setPromptText("不确定的步骤UUID");note.setPromptText("实际核对哪些文件/副作用，以及是否需要补做");Runnable refresh=()->work(out,()->stepReport());
        VBox b=body(new Label("started表示重启/异常后结果不确定；uncertain表示命令失败且可能部分执行。不要直接重跑。日志只保存参数和结果哈希，不保存命令中的密钥。"),new HBox(8,button("刷新步骤",refresh)),out,id,note,button("登记人工核对",()->{if(handle.running)throw new IllegalStateException("先暂停，避免核对与执行竞态");if(confirm("确认已实际核对该步骤？登记后允许继续，并不自动重跑。"))work(out,()->{handle.loop.reliability().acknowledge(id.getText().trim(),note.getText());return stepReport();});}));VBox.setVgrow(out,Priority.ALWAYS);refresh.run();return b;
    }
    private String stepReport()throws Exception {
        String taskId;synchronized(handle.session.taskState){taskId=handle.session.taskState.id;}
        ReliabilityService service=handle.loop.reliability();
        return "当前任务未核对步骤（taskId="+taskId+"）：\n"+gson.toJson(service.unresolved(taskId))
                +"\n\n本会话全部步骤（按时间排序）：\n"+gson.toJson(service.steps());
    }
    private CheckpointStore checkpoints(){Path root=Paths.get(handle.loop.workspace().workDir());return new CheckpointStore(root,root.resolve(".minion/checkpoints").resolve(handle.id));}
    private VBox changes(){TextArea out=area(false);TextField id=new TextField();id.setPromptText("检查点ID（列表中第一列）");VBox b=body(new Label("仅恢复被Minion记录过的文件；任意Shell/外部写操作不能保证回退。当前文件冲突时拒绝恢复。"),new HBox(8,button("列出检查点",()->work(out,()->gson.toJson(checkpoints().list(100)))),id),new HBox(8,button("查看前后 / 预检",()->work(out,()->checkpoints().diff(id.getText().trim())+"\n\n"+checkpoints().preflight(id.getText().trim()))),button("恢复整组",()->{if(handle.running)throw new IllegalStateException("先暂停当前任务");if(confirm("将恢复选中操作组的文件，恢复前自动备份当前版本。确认范围？"))work(out,()->checkpoints().restoreGroup(id.getText().trim()));}),button("人工批准当前版本",()->{if(handle.running)throw new IllegalStateException("先暂停当前任务");if(confirm("仅在已审阅差异且愿意覆盖当前版本时批准。此操作不恢复文件。"))work(out,()->{checkpoints().approveCurrent(id.getText().trim());return checkpoints().preflight(id.getText().trim());});})),out);VBox.setVgrow(out,Priority.ALWAYS);return b;}
    private ComboBox<String> models(){ComboBox<String> c=new ComboBox<String>();for(ModelConfig m:manager.models().list())c.getItems().add(m.displayName);c.setValue(manager.modelForSession(handle).displayName);return c;}
    private Path results(){return handle.loop.reliability().root().resolve("evaluations");}
    private VBox probes(){ComboBox<String> model=models();CheckBox image=new CheckBox("包含合成图片探测");TextArea out=area(false),samples=area(true),review=area(true);samples.setPrefRowCount(4);samples.setText("[{\"id\":\"synthetic-1\",\"prompt\":\"只回答OK\",\"expected\":\"OK\",\"match\":\"exact\"}]");
        review.setPrefRowCount(3);review.setText("{\"accepted\":false,\"expectedItems\":0,\"omittedItems\":0,\"duplicateItems\":0,\"incorrectItems\":0,\"note\":\"请填写人工复核依据\"}");
        Button probe=button("手动模型自检",()->{ModelConfig m=manager.models().get(model.getValue()).copy();boolean visual=image.isSelected();if(confirm("向选中接口发送3-4个合成请求，可能计费；不发送项目资料，不修改上下文设置。继续？"))work(out,()->{JsonObject result=ModelProbe.run(m,manager::newLlm,visual);saveResult("probe",result);return gson.toJson(result);});});
        Button evaluate=button("运行匿名评测",()->{ModelConfig m=manager.models().get(model.getValue()).copy();JsonArray data=JsonParser.parseString(samples.getText()).getAsJsonArray().deepCopy();if(confirm("确认下方样本无敏感业务资料且允许发送给选中接口？最多20次请求，每次最多1024输出token。"))work(out,()->{JsonObject result=ModelProbe.evaluate(m,data,manager::newLlm);saveResult("eval",result);return gson.toJson(result);});});
        VBox b=body(new Label("不是按模型名字猜能力：合成文字/工具/图片实测。小请求无法证明1M上下文；配置变化/过期使能力变为unknown；档案仅适用于关闭思考/1024输出的探测模式。匿名评测仅测试样本回答，不等同真实完整Agent任务评测。"),new HBox(8,model,image,probe),new FlowPane(8,8,button("查看当前能力档案",()->{String fp=ModelProbe.fingerprint(manager.models().get(model.getValue()).copy());work(out,()->capabilityStore().load(fp).describe(System.currentTimeMillis()).toString());}),button("作废能力档案",()->{String fp=ModelProbe.fingerprint(manager.models().get(model.getValue()));work(out,()->{capabilityStore().invalidate(fp,System.currentTimeMillis());return capabilityStore().load(fp).describe(System.currentTimeMillis()).toString();});}),button("客户端耗时 / token来源",()->work(out,()->telemetryReport())),button("清除本会话图片拒绝记录",()->{if(handle.llm instanceof DeepSeekClient){((DeepSeekClient)handle.llm).clearRejectedImageRequests();feedback.setText("已清除；未发送请求，下次由你手动重试。");}})),samples,new HBox(8,evaluate,button("本任务执行统计",()->work(out,()->gson.toJson(TaskEvaluation.current(handle.session,handle.loop.reliability())))),button("查看已保存结果",()->work(out,()->savedResults()))),new Label("人工业务复核（仅本地保存，不发送模型；漏处理/重复/错误需人工证据）"),review,button("登记本任务人工复核",()->{if(handle.running)throw new IllegalStateException("先等待当前任务结束再复核");if(confirm("确认这些数量由你核对过实际产物和原始资料？模型自述不能作为依据。")){String input=review.getText();String taskId=handle.session.taskState.id;work(out,()->gson.toJson(TaskEvaluation.recordReview(handle.loop.reliability(),handle.session.taskState,taskId,input)));}}),out);VBox.setVgrow(out,Priority.ALWAYS);return b;
    }
    private ModelCapabilityStore capabilityStore(){
        Path root=Paths.get(handle.loop.workspace().workDir()).resolve(".minion/model-capabilities");
        if(!com.minion.core.tools.PathsGuard.inside(handle.loop.workspace().workDir(),root))throw new IllegalStateException("能力档案目录越界");
        return new ModelCapabilityStore(root);
    }
    private String savedResults()throws Exception{
        List<String> lines=new ArrayList<String>();Path dir=results();
        if(Files.isDirectory(dir))try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir,"*.json")){
            for(Path p:stream){
                JsonObject r=JsonParser.parseString(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)).getAsJsonObject();
                ModelConfig m=r.has("model")?manager.models().get(r.get("model").getAsString()):null;
                boolean valid=m!=null&&r.has("fingerprint")&&ModelProbe.fingerprint(m).equals(r.get("fingerprint").getAsString());
                String label=valid?"[配置匹配，历史样本非能力保证] ":"[配置已失效] ";
                if(r.has("profile")){
                    ModelCapabilityProfile profile=capabilityStore().load(r.get("fingerprint").getAsString());
                    r.add("currentProfile",profile.describe(System.currentTimeMillis()));
                    label=valid&&profile.isCurrent(System.currentTimeMillis())?"[当前探测档案；仅所列模式] ":"[能力unknown：配置变化/过期/作废] ";
                }
                lines.add(label+p.getFileName()+"\n"+gson.toJson(r));
            }
        }
        return String.join("\n\n",lines);
    }
    private String telemetryReport(){JsonObject out=new JsonObject();out.addProperty("scope","当前客户端最近32次调用尝试（缓存拒绝不联网），含内部请求，不含其他独立子客户端；非当前任务独占。-1=unknown，网络生成跨度非纯服务端计算时间。runtimePhases为本进程当前任务阶段，工具按并行批次墙钟计时，未观测阶段保持unknown。");out.add("runtimePhases",handle.loop.phaseTimings());out.add("sessionUsageSources",handle.session.usage.breakdown());if(handle.llm instanceof DeepSeekClient)out.add("recentRequests",gson.toJsonTree(((DeepSeekClient)handle.llm).telemetry().recent()));else out.addProperty("recentRequests","unknown: client not instrumented");return gson.toJson(out);}
    private void saveResult(String kind,JsonObject result)throws Exception{if("probe".equals(kind))capabilityStore().save(ModelProbe.profile(result));Path p=results().resolve(kind+"-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".json");if(!com.minion.core.tools.PathsGuard.inside(handle.loop.workspace().workDir(),p))throw new IllegalStateException("结果目录越界");AtomicFiles.writeText(p,gson.toJson(result),StandardCharsets.UTF_8);}
    private VBox jobs(){TextArea input=area(true),out=area(false);input.setPrefRowCount(7);try{input.setText(manager.jobs(handle).json());}catch(Exception e){input.setText("[]");out.setText(e.getMessage());}
        VBox b=body(new Label("应用内计划任务：默认关闭，关闭Minion不运行。绑定本会话项目/模型，不与当前任务重叠。无历史补跑，监听仅目录第一层、稳定30秒后触发。自动轮次只允许所选只读工具；需要写入时须手动发起。模型设置改变或运行失败则暂停。"),new Label("timer示例：[{\"kind\":\"timer\",\"intervalMinutes\":60,\"prompt\":\"检查工作区已有报告，只读汇总\",\"allowedTools\":[\"Read\",\"Grep\"]}]；file示例将kind改file并填写watchDir。仅支持Read/ViewImage/Glob/Grep/Knowledge/Document/Excel的只读动作。保存会关闭全部任务，启用需另行确认。"),input,new HBox(8,button("保存（停用）",()->{try{manager.jobs(handle).configure(input.getText(),ModelProbe.fingerprint(manager.modelForSession(handle)));input.setText(manager.jobs(handle).json());}catch(Exception e){throw new IllegalStateException(e);}}),button("启用本会话计划",()->{if(confirm("确认在本会话按所列项目、模型和提示词定期执行？自动执行仅可使用指定的只读工具；请使用专门的计划任务会话避免干扰手动工作。")){try{manager.jobs(handle).enable(true);manager.armJobs(handle);out.setText("已启用。等待下次周期/新的稳定文件变动。");}catch(Exception e){throw new IllegalStateException(e);}}}),button("全部暂停",()->{try{manager.jobs(handle).enable(false);out.setText("已暂停后续调度；正在运行的任务需另点停止。");}catch(Exception e){throw new IllegalStateException(e);}}),button("刷新状态",()->{try{out.setText(manager.jobs(handle).json());}catch(Exception e){throw new IllegalStateException(e);}})),out);VBox.setVgrow(out,Priority.ALWAYS);return b;
    }
}
