package cn.qingyue.pdf;

/** Identity of a recycled row's latest asynchronous thumbnail request. UI-thread owned. */
final class ThumbnailBinding {
    private int page=-1,generation=-1;
    private long token;
    long bind(int page,int generation){this.page=page;this.generation=generation;return ++token;}
    boolean accepts(int page,int generation,long token){return this.page==page&&this.generation==generation&&this.token==token;}
    void clear(){page=-1;generation=-1;token++;}
}
