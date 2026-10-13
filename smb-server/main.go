package main
import("context";"encoding/json";"errors";"flag";"fmt";"log";"log/slog";"os";"os/signal";"strconv";"syscall";"time"
"github.com/sonroyaalmerol/go-smb-server/smb/auth"
"github.com/sonroyaalmerol/go-smb-server/smb/ntlmssp"
"github.com/sonroyaalmerol/go-smb-server/smb/server"
"github.com/sonroyaalmerol/go-smb-server/smb/vfs")
type ShareConfig struct{Name string;Root string;AllowUploads bool;AllowDeletes bool;AllowCreateFolders bool;ShowHiddenFiles bool}
type Config struct{Port int;FallbackPort int;StatusPath string;ServerName string;RequireAuth bool;Username string;Password string;Shares []ShareConfig}
type Status struct{Running bool;Port int;Shares []string;Message string}
func main(){
 p:=flag.String("config","","private LANVault SMB config");flag.Parse();if *p==""{log.Fatal("missing --config")}
 raw,e:=os.ReadFile(*p);if e!=nil{log.Fatalf("read config: %v",e)};var c Config
 if e=json.Unmarshal(raw,&c);e!=nil{log.Fatalf("parse config: %v",e)}
 if len(c.Shares)==0{writeStatus(c.StatusPath,Status{Message:"No directly mounted storage volumes available."});log.Fatal("no shares configured")}
 if c.Port<1||c.Port>65535{c.Port=445};if c.FallbackPort<1||c.FallbackPort>65535||c.FallbackPort==c.Port{c.FallbackPort=1445}
 if c.ServerName==""{c.ServerName="LANVAULT"}
 ctx,stop:=signal.NotifyContext(context.Background(),syscall.SIGINT,syscall.SIGTERM);defer stop();defer writeStatus(c.StatusPath,Status{Message:"SMB server stopped."})
 names:=make([]string,0,len(c.Shares));for _,s:=range c.Shares{names=append(names,s.Name)}
 var last error
 for _,port:=range []int{c.Port,c.FallbackPort}{if ctx.Err()!=nil{return};last=servePort(ctx,c,port,names);if ctx.Err()!=nil{return};if last==nil{return};log.Printf("SMB port %d failed: %v",port,last)}
 msg:="SMB server could not bind port 445 or fallback port 1445.";if last!=nil{msg+=" "+last.Error()};writeStatus(c.StatusPath,Status{Message:msg});log.Print(msg);os.Exit(1)
}
func servePort(ctx context.Context,c Config,port int,names []string)error{
 shares:=make([]vfs.Share,0,len(c.Shares));for _,sc:=range c.Shares{b,e:=newLocalBackend(sc.Root,sc.AllowUploads,sc.AllowDeletes,sc.AllowCreateFolders,sc.ShowHiddenFiles);if e!=nil{return fmt.Errorf("prepare %q: %w",sc.Name,e)};shares=append(shares,vfs.NewDiskShare(sc.Name,b))}
 var factory auth.Factory
 if c.RequireAuth{if c.Username==""||c.Password==""{return errors.New("missing SMB credentials")};creds:=ntlmssp.NewMemoryCredentials();creds.Add("WORKGROUP",c.Username,c.Password);creds.Add("",c.Username,c.Password);factory=ntlmssp.NewServer(creds,c.ServerName)}else{factory=auth.AlwaysAllowFactory()}
 logger:=slog.New(slog.NewTextHandler(os.Stdout,&slog.HandlerOptions{Level:slog.LevelInfo}))
 srv,e:=server.New(server.WithAddr("0.0.0.0:"+strconv.Itoa(port)),server.WithShares(shares...),server.WithAuth(factory),server.WithLogger(logger));if e!=nil{return e}
 result:=make(chan error,1);go func(){result<-srv.ListenAndServe(ctx)}();timer:=time.NewTimer(400*time.Millisecond);defer timer.Stop()
 select{case e:=<-result:return e;case <-ctx.Done():_ = srv.Shutdown();return nil;case <-timer.C:writeStatus(c.StatusPath,Status{Running:true,Port:port,Shares:names,Message:fmt.Sprintf("SMB2/3 listening on port %d.",port)})}
 return <-result
}
func writeStatus(file string,s Status){if file==""{return};raw,e:=json.Marshal(s);if e!=nil{return};if e=os.MkdirAll(dir(file),0700);e!=nil{return};tmp:=file+".tmp";if os.WriteFile(tmp,raw,0600)==nil{_ = os.Rename(tmp,file)}}
func dir(p string)string{for i:=len(p)-1;i>=0;i--{if p[i]=='/'{if i==0{return "/"};return p[:i]}};return "."}
