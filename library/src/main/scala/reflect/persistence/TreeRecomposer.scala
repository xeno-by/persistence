package scala.reflect.persistence

import scala.annotation.tailrec
import scala.language.postfixOps

/* TODO: test and adapt */
class TreeRecomposer[U <: scala.reflect.api.Universe](val u: U) {
  import u._
  import Enrichments._
  
  def apply(decTree: DecTree, start: Int = 0): Tree = {
    var names = decTree.names.unzipWithIdxs
    @tailrec def loop(trees: List[Node], dict: Map[Node, Tree], count: Int): Map[Node, Tree] = trees match {
      case Nil => dict
      case x :: xs =>
        val res = x.tpe match {
          case NodeTag.PackageDef =>
            PackageDef(dict(x.children.head).asInstanceOf[RefTree], x.children.tail map (dict(_)))
          case NodeTag.ClassDef =>
            ClassDef(NoMods, TypeName(names(count)), x.children.init map (dict(_).asInstanceOf[TypeDef]), dict(x.children.last).asInstanceOf[Template])
          case NodeTag.ModuleDef =>
            ModuleDef(NoMods, TermName(names(count)), dict(x.children.head).asInstanceOf[Template])
          case NodeTag.ValDef =>
            ValDef(NoMods, TermName(names(count)), dict(x.children.head), dict(x.children.last))
          case NodeTag.DefDef =>
            val params = x.children.dropRight(2).splitOn(_ == Node.separator)
            val vparams = params.tail.map(x => x.map(dict(_).asInstanceOf[ValDef]))
            DefDef(NoMods, TermName(names(count)), params.head.map(dict(_).asInstanceOf[TypeDef]), vparams, dict(x.children.init.last), dict(x.children.last))
          case NodeTag.TypeDef =>
            TypeDef(NoMods, TypeName(names(count)), x.children.init map (dict(_).asInstanceOf[TypeDef]), dict(x.children.last))
          case NodeTag.LabelDef =>
            LabelDef(TermName(names(count)), x.children.init map (dict(_).asInstanceOf[Ident]), dict(x.children.last))
          case NodeTag.Import =>
            Import(dict(x.children.head), Nil)
          case NodeTag.Template =>
            val children = x.children.splitOn(c => c.tpe == NodeTag.Separator).map(_.map(dict(_)))
            Template(children.head, children(1).head.asInstanceOf[ValDef], children.last)
          case NodeTag.Block =>
            Block(x.children.init map (dict(_)), dict(x.children.last))
          case NodeTag.CaseDef =>
            CaseDef(dict(x.children.head), dict(x.children(1)), dict(x.children.last))
          case NodeTag.Alternative =>
            Alternative(x.children map (dict(_)))
          case NodeTag.Star =>
            Star(dict(x.children.head))
          case NodeTag.Bind =>
            Bind(TermName(names(count)), dict(x.children.head))
          case NodeTag.UnApply =>
            UnApply(dict(x.children.head), x.children.tail map (dict(_)))
          case NodeTag.ArrayValue => ??? /* TODO */
          /*ArrayValue(dict(x.children.head), x.children.tail map (dict(_)))*/
          case NodeTag.Function =>
            Function(x.children.init map (dict(_).asInstanceOf[ValDef]), dict(x.children.last))
          case NodeTag.Assign =>
            Assign(dict(x.children.head), dict(x.children.last))
          case NodeTag.AssignOrNamedArg =>
            AssignOrNamedArg(dict(x.children.head), dict(x.children.last))
          case NodeTag.If =>
            If(dict(x.children.head), dict(x.children(1)), dict(x.children.last))
          case NodeTag.Match =>
            Match(dict(x.children.head), x.children.tail map (dict(_).asInstanceOf[CaseDef]))
          case NodeTag.Return =>
            Return(dict(x.children.head))
          case NodeTag.Try =>
            Try(dict(x.children.head), x.children.tail.init map (dict(_).asInstanceOf[CaseDef]), dict(x.children.last))
          case NodeTag.Throw =>
            Throw(dict(x.children.head))
          case NodeTag.New =>
            New(dict(x.children.head))
          case NodeTag.Typed =>
            Typed(dict(x.children.head), dict(x.children.last))
          case NodeTag.TypeApply =>
            TypeApply(dict(x.children.head), x.children.tail map (dict(_)))
          case NodeTag.Apply =>
            Apply(dict(x.children.head), x.children.tail map (dict(_)))
          case NodeTag.This =>
            This(TypeName(names(count)))
          case NodeTag.Select =>
            Select(dict(x.children.head), TermName(names(count)))
          case NodeTag.Ident =>
            Ident(TermName(names(count)))
          case NodeTag.ReferenceToBoxed =>
            ReferenceToBoxed(dict(x.children.head).asInstanceOf[Ident])
          case NodeTag.Literal =>
            Literal(Constant(0))
          case NodeTag.Annotated =>
            Annotated(dict(x.children.head), dict(x.children.last))
          case NodeTag.SingletonTypeTree =>
            SingletonTypeTree(dict(x.children.head))
          case NodeTag.SelectFromTypeTree =>
            SelectFromTypeTree(dict(x.children.head), TypeName(names(count)))
          case NodeTag.CompoundTypeTree =>
            CompoundTypeTree(dict(x.children.head).asInstanceOf[Template])
          case NodeTag.AppliedTypeTree =>
            AppliedTypeTree(dict(x.children.head), x.children.tail map (dict(_)))
          case NodeTag.TypeBoundsTree =>
            TypeBoundsTree(dict(x.children.head), dict(x.children.last))
          case NodeTag.ExistentialTypeTree =>
            ExistentialTypeTree(dict(x.children.head), x.children.tail map (dict(_).asInstanceOf[MemberDef]))
          case NodeTag.TypeTree =>
            TypeTree()
          case NodeTag.Super =>
            Super(dict(x.children.head), TypeName(names(count)))
          case NodeTag.EmptyTree => EmptyTree
          case _ => sys.error(x.getClass().toString()) /* Should never happen */
        }
        loop(xs, dict + (x -> res), count - 1)
    }
    val flattenTree = decTree.tree.flattenBFS.filter(x => x.tpe != NodeTag.Separator && x.tpe != NodeTag.EmptyTree) 
    //flattenTree foreach {v => println(v.tpe)}
    loop(flattenTree, Map((Node.empty -> EmptyTree)), flattenTree.size - 1 - start)(decTree.tree)
  }
}
