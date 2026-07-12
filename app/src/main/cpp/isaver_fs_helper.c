#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
enum{X_USAGE=64,X_INVALID=65,X_NOT_FOUND=44,X_EXISTS=49,X_NOSPACE=50,X_IO=51};
static int name_ok(const char*s){return s&&*s&&strcmp(s,".")&&strcmp(s,"..")&&!strchr(s,'/')&&strlen(s)<=255;}
static int u64(const char*s,unsigned long long*v){char*e;errno=0;*v=strtoull(s,&e,10);return !errno&&e&&!*e;}
static int errx(int e){return e==EEXIST?X_EXISTS:e==ENOSPC?X_NOSPACE:e==ENOENT?X_NOT_FOUND:X_IO;}
static int dirfd_checked(const char*p,unsigned long long dev,unsigned long long ino){int d=open(p,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);struct stat s;if(d<0)return-(errno==ENOENT?X_NOT_FOUND:X_INVALID);if(fstat(d,&s)||s.st_dev!=(dev_t)dev||s.st_ino!=(ino_t)ino){close(d);return-X_INVALID;}return d;}
static int parse_id(char**a,int i,unsigned long long*d,unsigned long long*n){return u64(a[i],d)&&u64(a[i+1],n);}
static int copy_temp(int c,char**a){
 if(c!=10||!name_ok(a[3]))return X_USAGE;unsigned long long pd,pi,sd,si,size;if(!parse_id(a,5,&pd,&pi)||!parse_id(a,7,&sd,&si)||!u64(a[9],&size))return X_USAGE;
 int d=dirfd_checked(a[2],pd,pi);if(d<0)return-d;int s=open(a[4],O_RDONLY|O_NOFOLLOW|O_CLOEXEC);struct stat ss;if(s<0){close(d);return errx(errno);}if(fstat(s,&ss)||!S_ISREG(ss.st_mode)||ss.st_dev!=(dev_t)sd||ss.st_ino!=(ino_t)si||(unsigned long long)ss.st_size!=size){close(s);close(d);return X_INVALID;}
 int t=openat(d,a[3],O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(t<0){int e=errno;close(s);close(d);return errx(e);}char b[65536];unsigned long long n=0;int out=0;for(;;){ssize_t r=read(s,b,sizeof b);if(r<0){out=errx(errno);break;}if(!r)break;for(ssize_t o=0;o<r;){ssize_t w=write(t,b+o,(size_t)(r-o));if(w<0){out=errx(errno);goto done;}o+=w;n+=(unsigned long long)w;}}
 if(n!=size||fsync(t))out=errx(errno?errno:EIO);done:if(out)unlinkat(d,a[3],0);else{struct stat ts;if(fstat(t,&ts))out=X_IO;else printf("%llu:%llu:%llu\n",(unsigned long long)ts.st_dev,(unsigned long long)ts.st_ino,n);}close(t);close(s);close(d);return out;
}
static int publish(int c,char**a){
 if(c!=10||!name_ok(a[3])||!name_ok(a[4]))return X_USAGE;unsigned long long pd,pi,td,ti,size;if(!parse_id(a,5,&pd,&pi)||!parse_id(a,7,&td,&ti)||!u64(a[9],&size))return X_USAGE;int d=dirfd_checked(a[2],pd,pi);if(d<0)return-d;struct stat t;if(fstatat(d,a[3],&t,AT_SYMLINK_NOFOLLOW)||!S_ISREG(t.st_mode)||t.st_dev!=(dev_t)td||t.st_ino!=(ino_t)ti||(unsigned long long)t.st_size!=size){close(d);return X_INVALID;}long r=syscall(SYS_renameat2,d,a[3],d,a[4],RENAME_NOREPLACE);int e=errno;if(!r)printf("%llu:%llu:%llu\n",td,ti,size);close(d);return r?errx(e):0;
}
static int remove_temp(int c,char**a){if(c!=8||!name_ok(a[3]))return X_USAGE;unsigned long long pd,pi,td,ti;if(!parse_id(a,4,&pd,&pi)||!parse_id(a,6,&td,&ti))return X_USAGE;int d=dirfd_checked(a[2],pd,pi);if(d<0)return-d;struct stat t;if(fstatat(d,a[3],&t,AT_SYMLINK_NOFOLLOW)||!S_ISREG(t.st_mode)||t.st_dev!=(dev_t)td||t.st_ino!=(ino_t)ti){close(d);return X_INVALID;}int r=unlinkat(d,a[3],0),e=errno;close(d);return r?errx(e):0;}
int main(int c,char**a){if(c<2)return X_USAGE;if(!strcmp(a[1],"copy-to-temp"))return copy_temp(c,a);if(!strcmp(a[1],"publish-noreplace"))return publish(c,a);if(!strcmp(a[1],"remove-temp"))return remove_temp(c,a);return X_USAGE;}
